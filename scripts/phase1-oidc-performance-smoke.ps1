param(
    [string] $DockerHost = "ssh://admin1@192.168.0.41",
    [string] $SshTarget = "admin1@192.168.0.41",
    [string] $Duration = "30m"
)

$ErrorActionPreference = "Stop"
$keycloakImage = "quay.io/keycloak/keycloak:26.8.1@sha256:3f3c5f8e03c614774b4bd9173b88391fefa4153bfc010d8012d59ad825377332"
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
$contextName = "onecard-phase1-oidc-context-$suffix"
$keycloakName = "onecard-phase1-keycloak-$suffix"
$gatewayName = "onecard-phase1-gateway-$suffix"
$accountName = "onecard-phase1-account-$suffix"
$gatewayImage = "onecard-phase1-gateway:$suffix"
$accountImage = "onecard-phase1-account:$suffix"
$remoteDirectory = "/tmp/onecard-phase1-$suffix"
$k6ArchivePath = Join-Path ([IO.Path]::GetTempPath()) "onecard-k6-v2.3.0-$suffix.tar.gz"
$k6Sha256 = "39c3117b6af817592dcd0ce4242105c0a7af10948c2a425306f0be8f7a8a8ab1"
$outputDirectory = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "artifacts/phase1-platform-$suffix"
$savedDockerContext = $env:DOCKER_CONTEXT
$dockerContextCreated = $false
$keycloakCreated = $false
$gatewayCreated = $false
$accountCreated = $false
$sshTunnel = $null
$stubPid = $null
$k6Started = $false
$httpClient = [System.Net.Http.HttpClient]::new()
$savedEnvironment = @{}
$environmentNames = @(
    "ONECARD_KEYCLOAK_IT_ENABLED",
    "ONECARD_KEYCLOAK_IT_CLIENT_ID",
    "ONECARD_KEYCLOAK_IT_CLIENT_SECRET",
    "ONECARD_KEYCLOAK_IT_SCOPE",
    "ONECARD_KEYCLOAK_IT_TOKEN_URI",
    "ONECARD_KEYCLOAK_IT_ISSUER",
    "ONECARD_KEYCLOAK_IT_JWK_SET_URI",
    "BASE_URL",
    "AUTH_TOKEN",
    "DURATION",
    "VUS"
)

function New-TestPassword {
    $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Get-FreeLocalPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

function Get-HttpResponse([string] $Uri, [string] $AccessToken, [string] $Traceparent) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $Uri)
    if ($AccessToken) {
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $AccessToken)
    }
    if ($Traceparent) {
        $request.Headers.TryAddWithoutValidation("traceparent", $Traceparent) | Out-Null
    }
    $response = $httpClient.SendAsync($request).GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $traceparentResponse = if ($response.Headers.Contains("traceparent")) {
        $response.Headers.GetValues("traceparent") | Select-Object -First 1
    } else {
        ""
    }
    return [pscustomobject]@{
        StatusCode = [int]$response.StatusCode
        Body = $body
        Traceparent = $traceparentResponse
    }
}

function Get-KeycloakToken([string] $TokenUri, [hashtable] $Form, [string] $BasicAuth) {
    $headers = @{}
    if ($BasicAuth) {
        $headers.Authorization = "Basic $BasicAuth"
    }
    return Invoke-RestMethod -Method Post -Uri $TokenUri -Headers $headers `
        -ContentType "application/x-www-form-urlencoded" -Body $Form
}

function Get-JwtClaims([string] $Token) {
    $payload = $Token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    switch ($payload.Length % 4) {
        2 { $payload += '==' }
        3 { $payload += '=' }
    }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload)) | ConvertFrom-Json
}

function Invoke-KeycloakAdmin([string] $Method, [string] $Path, [object] $Body = $null) {
    $parameters = @{
        Method = $Method
        Uri = "$localKeycloakBase$Path"
        Headers = @{ Authorization = "Bearer $adminToken" }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = ConvertTo-Json -InputObject $Body -Depth 20 -Compress
    }
    try {
        return Invoke-RestMethod @parameters
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { "unknown" }
        throw "Keycloak Admin API $Method $Path returned HTTP $($status): $($_.ErrorDetails.Message)"
    }
}

function Wait-ForHttp([string] $Uri, [int] $ExpectedStatus, [int] $Attempts = 90) {
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $response = Get-HttpResponse $Uri "" ""
            if ($response.StatusCode -eq $ExpectedStatus) {
                return
            }
        } catch {
            Start-Sleep -Seconds 2
            continue
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for HTTP $ExpectedStatus from $Uri."
}

function Invoke-Docker([string[]] $Arguments, [string] $FailureMessage) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw $FailureMessage
    }
}

function Review-TestLog([string] $Text) {
    $containsCredentials = $false
    $secrets = @(
        $adminPassword,
        $adminToken,
        $masterToken,
        $userPassword,
        $serviceClientSecret,
        $userToken.access_token,
        $serviceToken.access_token,
        $scopeMissingToken.access_token
    ) | Where-Object { $_ -is [string] -and $_.Length -ge 8 } | Select-Object -Unique
    foreach ($secret in $secrets) {
        if ($Text.Contains($secret)) {
            $containsCredentials = $true
            $Text = $Text.Replace($secret, "[REDACTED]")
        }
    }
    if ($Text -match '(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*') {
        $containsCredentials = $true
        $Text = [regex]::Replace($Text, '(?i)(\bBearer\s+)[A-Za-z0-9._~+/-]+=*', '$1[REDACTED]')
    }
    if ($Text -match '(?i)\b(?:client[_-]?secret|password)\s*[:=]\s*\S+') {
        $containsCredentials = $true
        $Text = [regex]::Replace($Text, '(?i)(\b(?:client[_-]?secret|password)\s*[:=]\s*)\S+', '$1[REDACTED]')
    }
    return [pscustomobject]@{ ContainsCredentials = $containsCredentials; Text = $Text }
}

foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    if ([bool]$DockerHost -ne [bool]$SshTarget) {
        throw "DockerHost and SshTarget must be provided together."
    }
    if (-not $SshTarget -and -not (Get-Command k6 -ErrorAction SilentlyContinue)) {
        throw "k6 is required locally when no development server is configured."
    }

    $listeners = ssh -o BatchMode=yes -o ConnectTimeout=10 $SshTarget "ss -H -ltn"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect loopback ports on the development server."
    }
    $usedPorts = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($match in [regex]::Matches(($listeners -join "`n"), ":(\d+)\s")) {
        [void]$usedPorts.Add([int]$match.Groups[1].Value)
    }
    $remoteBasePort = 0
    for ($attempt = 0; $attempt -lt 500; $attempt++) {
        $candidate = Get-Random -Minimum 22000 -Maximum 47000
        if ((0..4 | Where-Object { $usedPorts.Contains($candidate + $_) }).Count -eq 0) {
            $remoteBasePort = $candidate
            break
        }
    }
    if ($remoteBasePort -eq 0) {
        throw "Unable to choose an unused development-server port range."
    }
    $remoteKeycloakPort = $remoteBasePort
    $remoteGatewayPort = $remoteBasePort + 1
    $remoteManagementPort = $remoteBasePort + 2
    $remoteAccountPort = $remoteBasePort + 3
    $remoteStubPort = $remoteBasePort + 4
    $localKeycloakPort = Get-FreeLocalPort
    $localGatewayPort = Get-FreeLocalPort
    $localManagementPort = Get-FreeLocalPort
    $localAccountPort = Get-FreeLocalPort

    docker context create $contextName --docker "host=$DockerHost" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create the temporary Docker context."
    }
    $dockerContextCreated = $true
    $env:DOCKER_CONTEXT = $contextName

    & .\mvnw.cmd --batch-mode --no-transfer-progress -pl gateway,services/onecard-account-service -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to package the Gateway and account service smoke images."
    }
    Invoke-Docker @("build", "--file", "gateway/Dockerfile", "--tag", $gatewayImage, "gateway") `
        "Unable to build the temporary Gateway smoke image."
    Invoke-Docker @("build", "--file", "services/onecard-account-service/Dockerfile", "--tag", $accountImage, "services/onecard-account-service") `
        "Unable to build the temporary account-service smoke image."

    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $hostEvidence = ssh -o BatchMode=yes $SshTarget "uname -a; nproc; free -h; docker version --format '{{.Server.Version}}'"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to capture development-server version and capacity information."
    }
    $hostEvidence | Set-Content -LiteralPath (Join-Path $outputDirectory "environment.txt")

    ssh $SshTarget "mkdir -p '$remoteDirectory'"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create the temporary remote smoke directory."
    }
    if ($SshTarget) {
        Invoke-WebRequest -Uri "https://api.github.com/repos/grafana/k6/releases/assets/579287772" `
            -Headers @{ "User-Agent" = "onecard-phase1-validation"; Accept = "application/octet-stream" } `
            -OutFile $k6ArchivePath
        $downloadedHash = (Get-FileHash -LiteralPath $k6ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($downloadedHash -ne $k6Sha256) {
            throw "The downloaded k6 release archive did not match its pinned SHA-256."
        }
        $remoteArchive = "$remoteDirectory/k6-v2.3.0-linux-amd64.tar.gz"
        scp $k6ArchivePath "$($SshTarget):$remoteArchive" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to copy the verified k6 release archive to the development server."
        }
        $remoteK6Script = "$remoteDirectory/gateway-platform.js"
        $remoteK6Runner = "$remoteDirectory/run-k6-remote.sh"
        scp (Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "tests/performance/gateway-platform.js") `
            "$($SshTarget):$remoteK6Script" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to copy the Gateway performance scenario to the development server."
        }
        scp (Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "tests/performance/run-k6-remote.sh") `
            "$($SshTarget):$remoteK6Runner" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to copy the remote k6 runner to the development server."
        }
        $remoteSetup = "cd '$remoteDirectory' && tar -xzf '$remoteArchive' && chmod 700 '$remoteK6Runner' 'k6-v2.3.0-linux-amd64/k6' && test -x 'k6-v2.3.0-linux-amd64/k6'"
        ssh $SshTarget $remoteSetup
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to extract and prepare the pinned k6 binary on the development server."
        }
    }
    $stubSource = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "tests/performance/gateway_upstream.py"
    $remoteStub = "$remoteDirectory/gateway_upstream.py"
    $remoteStubLog = "$remoteDirectory/gateway_upstream.log"
    scp $stubSource "$($SshTarget):$remoteStub" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to copy the temporary upstream stub to the development server."
    }
    $stubStart = "nohup python3 -u '$remoteStub' $remoteStubPort > '$remoteStubLog' 2>&1 < /dev/null & echo `$!"
    $stubPid = ssh $SshTarget $stubStart
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($stubPid)) {
        throw "Unable to start the temporary upstream stub."
    }

    $adminPassword = New-TestPassword
    Invoke-Docker @(
        "create", "--name", $keycloakName, "--network", "host", "--memory", "1g", "--cpus", "2",
        "--env", "KC_BOOTSTRAP_ADMIN_USERNAME=phase1-admin",
        "--env", "KC_BOOTSTRAP_ADMIN_PASSWORD=$adminPassword",
        "--env", "KC_HOSTNAME=http://127.0.0.1:$remoteKeycloakPort",
        "--env", "KC_HOSTNAME_STRICT=false",
        "--env", "KC_HTTP_ENABLED=true",
        "--env", "KC_HTTP_PORT=$remoteKeycloakPort",
        "--env", "KC_HTTP_HOST=127.0.0.1",
        $keycloakImage, "start-dev"
    ) "Unable to create the temporary Keycloak container."
    $keycloakCreated = $true

    $sshArguments = @(
        "-N",
        "-o", "ExitOnForwardFailure=yes",
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=10"
    )
    foreach ($mapping in @(
        @{ Local = $localKeycloakPort; Remote = $remoteKeycloakPort },
        @{ Local = $localGatewayPort; Remote = $remoteGatewayPort },
        @{ Local = $localManagementPort; Remote = $remoteManagementPort },
        @{ Local = $localAccountPort; Remote = $remoteAccountPort }
    )) {
        $sshArguments += @("-L", "127.0.0.1:$($mapping.Local):127.0.0.1:$($mapping.Remote)")
    }
    $sshArguments += $SshTarget
    $sshTunnel = Start-Process -FilePath "ssh" -ArgumentList $sshArguments -PassThru -WindowStyle Hidden
    $tunnelReady = $false
    for ($attempt = 1; $attempt -le 100; $attempt++) {
        if ($sshTunnel.HasExited) {
            throw "The SSH tunnel exited before it became ready."
        }
        $listenerState = Get-NetTCPConnection -State Listen -LocalPort $localKeycloakPort -ErrorAction SilentlyContinue
        if ($listenerState) {
            $tunnelReady = $true
            break
        }
        Start-Sleep -Milliseconds 100
    }
    if (-not $tunnelReady) {
        throw "The SSH tunnel did not become ready."
    }

    Invoke-Docker @("start", $keycloakName) "Unable to start the temporary Keycloak server."
    $localKeycloakBase = "http://127.0.0.1:$localKeycloakPort"
    $remoteKeycloakBase = "http://127.0.0.1:$remoteKeycloakPort"
    Wait-ForHttp "$localKeycloakBase/realms/master/.well-known/openid-configuration" 200 180
    $masterToken = Get-KeycloakToken "$localKeycloakBase/realms/master/protocol/openid-connect/token" @{
        grant_type = "password"
        client_id = "admin-cli"
        username = "phase1-admin"
        password = $adminPassword
    } ""
    $adminToken = $masterToken.access_token
    Invoke-KeycloakAdmin "Post" "/admin/realms" @{
        realm = "onecard-dev"
        enabled = $true
        accessTokenLifespan = 3600
    }
    Invoke-KeycloakAdmin "Post" "/admin/realms/onecard-dev/roles" @{ name = "onecard-user" }
    Invoke-KeycloakAdmin "Post" "/admin/realms/onecard-dev/roles" @{ name = "onecard-admin" }
    Invoke-KeycloakAdmin "Post" "/admin/realms/onecard-dev/roles" @{ name = "onecard-auditor" }

    Invoke-KeycloakAdmin "Post" "/admin/realms/onecard-dev/client-scopes" @{
        name = "onecard.account.invoke"
        protocol = "openid-connect"
    }
    $clientScopes = Invoke-KeycloakAdmin "Get" "/admin/realms/onecard-dev/client-scopes"
    $serviceScope = $clientScopes | Where-Object name -eq "onecard.account.invoke" | Select-Object -First 1
    $roleScope = $clientScopes | Where-Object name -eq "roles" | Select-Object -First 1
    if (-not $serviceScope -or -not $roleScope) {
        throw "The temporary Keycloak realm did not create its service or role client scopes."
    }
    $serviceClientSecret = New-TestPassword
    foreach ($client in @(
        @{
            clientId = "onecard-api"
            enabled = $true
            protocol = "openid-connect"
            publicClient = $true
            standardFlowEnabled = $false
            directAccessGrantsEnabled = $true
        },
        @{
            clientId = "onecard-transaction-to-account"
            enabled = $true
            protocol = "openid-connect"
            publicClient = $false
            clientAuthenticatorType = "client-secret"
            secret = $serviceClientSecret
            serviceAccountsEnabled = $true
            standardFlowEnabled = $false
            directAccessGrantsEnabled = $false
        }
        ,@{
            clientId = "onecard-account-service"
            enabled = $true
            protocol = "openid-connect"
            publicClient = $true
            standardFlowEnabled = $false
            directAccessGrantsEnabled = $false
        }
    )) {
        Invoke-KeycloakAdmin "Post" "/admin/realms/onecard-dev/clients" $client
    }
    $clients = Invoke-KeycloakAdmin "Get" "/admin/realms/onecard-dev/clients"
    $apiClient = $clients | Where-Object clientId -eq "onecard-api" | Select-Object -First 1
    $serviceClient = $clients | Where-Object clientId -eq "onecard-transaction-to-account" | Select-Object -First 1
    if (-not $apiClient -or -not $serviceClient) {
        throw "The temporary Keycloak clients were not created."
    }
    Invoke-KeycloakAdmin "Put" "/admin/realms/onecard-dev/clients/$($apiClient.id)/default-client-scopes/$($roleScope.id)"
    Invoke-KeycloakAdmin "Post" "/admin/realms/onecard-dev/clients/$($apiClient.id)/protocol-mappers/models" @{
        name = "onecard-api-audience"
        protocol = "openid-connect"
        protocolMapper = "oidc-audience-mapper"
        config = @{
            "included.client.audience" = "onecard-api"
            "access.token.claim" = "true"
            "id.token.claim" = "false"
        }
    }
    Invoke-KeycloakAdmin "Put" "/admin/realms/onecard-dev/clients/$($serviceClient.id)/default-client-scopes/$($roleScope.id)"
    Invoke-KeycloakAdmin "Put" "/admin/realms/onecard-dev/clients/$($serviceClient.id)/optional-client-scopes/$($serviceScope.id)"
    Invoke-KeycloakAdmin "Post" "/admin/realms/onecard-dev/clients/$($serviceClient.id)/protocol-mappers/models" @{
        name = "onecard-account-audience"
        protocol = "openid-connect"
        protocolMapper = "oidc-audience-mapper"
        config = @{
            "included.client.audience" = "onecard-account-service"
            "access.token.claim" = "true"
            "id.token.claim" = "false"
        }
    }
    $userPassword = New-TestPassword
    Invoke-KeycloakAdmin "Post" "/admin/realms/onecard-dev/users" @{
        username = "phase1-smoke-user"
        firstName = "Phase1"
        lastName = "Smoke"
        email = "phase1-smoke-user@invalid.example"
        emailVerified = $true
        enabled = $true
        requiredActions = @()
    }
    $users = Invoke-KeycloakAdmin "Get" "/admin/realms/onecard-dev/users?username=phase1-smoke-user&exact=true"
    $testUser = $users | Select-Object -First 1
    $userRole = Invoke-KeycloakAdmin "Get" "/admin/realms/onecard-dev/roles/onecard-user"
    if (-not $testUser -or -not $userRole) {
        throw "The temporary Keycloak user or realm role could not be resolved."
    }
    Invoke-KeycloakAdmin "Put" "/admin/realms/onecard-dev/users/$($testUser.id)/reset-password" @{
        type = "password"
        value = $userPassword
        temporary = $false
    }
    Invoke-KeycloakAdmin "Post" "/admin/realms/onecard-dev/users/$($testUser.id)/role-mappings/realm" @($userRole)
    $adminToken = $null

    $realmIssuer = "$remoteKeycloakBase/realms/onecard-dev"
    $tokenUri = "$localKeycloakBase/realms/onecard-dev/protocol/openid-connect/token"
    $jwkSetUri = "$localKeycloakBase/realms/onecard-dev/protocol/openid-connect/certs"

    Invoke-Docker @(
        "run", "--detach", "--name", $accountName, "--network", "host", "--memory", "1g", "--cpus", "2",
        "--env", "SPRING_PROFILES_ACTIVE=dev",
        "--env", "SPRING_CLOUD_NACOS_CONFIG_ENABLED=false",
        "--env", "SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=false",
        "--env", "SERVER_ADDRESS=127.0.0.1",
        "--env", "SERVER_PORT=$remoteAccountPort",
        "--env", "OIDC_ISSUER_URI=$realmIssuer",
        "--env", "OIDC_JWK_SET_URI=http://127.0.0.1:$remoteKeycloakPort/realms/onecard-dev/protocol/openid-connect/certs",
        "--env", "OIDC_AUDIENCE=onecard-account-service",
        "--env", "ONECARD_DEV_SERVICE_AUTH_PROBE_ENABLED=true",
        "--env", "ACCOUNT_DB_ENABLED=false",
        $accountImage
    ) "Unable to start the temporary account-service verifier."
    $accountCreated = $true

    Invoke-Docker @(
        "run", "--detach", "--name", $gatewayName, "--network", "host", "--memory", "1536m", "--cpus", "2",
        "--env", "SPRING_PROFILES_ACTIVE=performance",
        "--env", "SERVER_ADDRESS=127.0.0.1",
        "--env", "SERVER_PORT=$remoteGatewayPort",
        "--env", "MANAGEMENT_SERVER_ADDRESS=127.0.0.1",
        "--env", "MANAGEMENT_SERVER_PORT=$remoteManagementPort",
        "--env", "ONECARD_PERF_UPSTREAM_URL=http://127.0.0.1:$remoteStubPort",
        "--env", "OIDC_ISSUER_URI=$realmIssuer",
        "--env", "OIDC_JWK_SET_URI=http://127.0.0.1:$remoteKeycloakPort/realms/onecard-dev/protocol/openid-connect/certs",
        "--env", "OIDC_AUDIENCE=onecard-api",
        $gatewayImage
    ) "Unable to start the temporary Gateway performance target."
    $gatewayCreated = $true

    Wait-ForHttp "http://127.0.0.1:$localAccountPort/actuator/health" 200
    Wait-ForHttp "http://127.0.0.1:$localManagementPort/actuator/health" 200

    try {
        $userToken = Get-KeycloakToken $tokenUri @{
            grant_type = "password"
            client_id = "onecard-api"
            username = "phase1-smoke-user"
            password = $userPassword
        } ""
    } catch {
        throw "Keycloak user password grant failed: $($_.ErrorDetails.Message)"
    }
    $serviceBasic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(
        "onecard-transaction-to-account:$serviceClientSecret"))
    try {
        $serviceToken = Get-KeycloakToken $tokenUri @{
            grant_type = "client_credentials"
            scope = "onecard.account.invoke"
        } $serviceBasic
        $scopeMissingToken = Get-KeycloakToken $tokenUri @{ grant_type = "client_credentials" } $serviceBasic
    } catch {
        throw "Keycloak service client-credentials grant failed: $($_.ErrorDetails.Message)"
    }
    $userClaims = Get-JwtClaims $userToken.access_token
    $serviceClaims = Get-JwtClaims $serviceToken.access_token
    $hasAccountAudience = @($serviceClaims.aud) -contains "onecard-account-service"
    $hasAccountScope = @($serviceClaims.scope -split '\s+') -contains "onecard.account.invoke"
    $hasUserRole = @($userClaims.realm_access.roles) -contains "onecard-user"
    if ($userClaims.iss -ne $realmIssuer -or @($userClaims.aud) -notcontains "onecard-api" -or -not $hasUserRole) {
        throw "Keycloak user token claims did not match the Gateway issuer, audience, and role."
    }
    if ($serviceClaims.iss -ne $realmIssuer -or -not $hasAccountAudience -or -not $hasAccountScope) {
        throw "Keycloak service token claim mismatch: issuer=$($serviceClaims.iss -eq $realmIssuer), audience=$hasAccountAudience ($(@($serviceClaims.aud) -join ',')), scope=$hasAccountScope."
    }
    [ordered]@{
        issuer = $realmIssuer
        user = @{ audience = @($userClaims.aud); roles = @($userClaims.realm_access.roles) }
        service = @{ audience = @($serviceClaims.aud); scope = $serviceClaims.scope }
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputDirectory "keycloak-claims.json")

    $traceId = [guid]::NewGuid().ToString("N")
    $traceparent = "00-$traceId-0123456789abcdef-01"
    $gatewayResponse = Get-HttpResponse "http://127.0.0.1:$localGatewayPort/api/account/platform-probe" `
        $userToken.access_token $traceparent
    if ($gatewayResponse.StatusCode -ne 200 -or $gatewayResponse.Body -notmatch '"status"\s*:\s*"stub"') {
        throw "The Gateway did not authenticate the Keycloak user token and proxy the request to the stub."
    }
    if (-not $gatewayResponse.Traceparent.StartsWith("00-$traceId-")) {
        throw "The Gateway did not return the trusted W3C trace context."
    }
    if ((Get-HttpResponse "http://127.0.0.1:$localGatewayPort/api/account/platform-probe" "" "").StatusCode -ne 401) {
        throw "The Gateway did not reject an anonymous API request."
    }
    $authorizedAccountResponse = Get-HttpResponse "http://127.0.0.1:$localAccountPort/internal/dev/service-auth-probe" `
        $serviceToken.access_token ""
    if ($authorizedAccountResponse.StatusCode -ne 200 -or $authorizedAccountResponse.Body -notmatch '"status"\s*:\s*"authenticated"') {
        throw "The account service did not accept the scoped Keycloak service token: HTTP $($authorizedAccountResponse.StatusCode)."
    }
    if ((Get-HttpResponse "http://127.0.0.1:$localAccountPort/internal/dev/service-auth-probe" `
            $scopeMissingToken.access_token "").StatusCode -ne 403) {
        throw "The account service did not reject a Keycloak service token without the required scope."
    }

    $env:ONECARD_KEYCLOAK_IT_ENABLED = "true"
    $env:ONECARD_KEYCLOAK_IT_CLIENT_ID = "onecard-transaction-to-account"
    $env:ONECARD_KEYCLOAK_IT_CLIENT_SECRET = $serviceClientSecret
    $env:ONECARD_KEYCLOAK_IT_SCOPE = "onecard.account.invoke"
    $env:ONECARD_KEYCLOAK_IT_TOKEN_URI = $tokenUri
    $env:ONECARD_KEYCLOAK_IT_ISSUER = $realmIssuer
    $env:ONECARD_KEYCLOAK_IT_JWK_SET_URI = $jwkSetUri
    $transactionTestLog = Join-Path $outputDirectory "transaction-keycloak-it.log"
    $transactionTestOutput = & .\mvnw.cmd --batch-mode --no-transfer-progress `
        -pl services/onecard-transaction-service -am `
        '-Dtest=ServiceClientCredentialsKeycloakIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    $transactionTestExitCode = $LASTEXITCODE
    $transactionLogReview = Review-TestLog ($transactionTestOutput -join "`n")
    $transactionLogReview.Text | Set-Content -LiteralPath $transactionTestLog
    if ($transactionLogReview.ContainsCredentials) {
        throw "The Keycloak integration test output contained a sensitive value; a redacted copy was saved to $transactionTestLog."
    }
    if ($transactionTestExitCode -ne 0) {
        throw "The transaction service did not acquire and propagate a verifiable Keycloak client-credentials token."
    }

    $env:BASE_URL = "http://127.0.0.1:$localGatewayPort"
    $env:AUTH_TOKEN = $userToken.access_token
    $k6Path = if (-not $SshTarget) { (Get-Command k6).Source } else { $null }
    $resourcePath = Join-Path $outputDirectory "container-resources.csv"
    "timestamp,name,cpu,memory" | Set-Content -LiteralPath $resourcePath
    $stageResults = [System.Collections.Generic.List[object]]::new()
    $activeStage = $null
    $stages = @(
        @{ VirtualUsers = 50; Duration = "1m" },
        @{ VirtualUsers = 100; Duration = "1m" },
        @{ VirtualUsers = 250; Duration = "1m" },
        @{ VirtualUsers = 500; Duration = $Duration }
    )
    foreach ($stage in $stages) {
        $activeStage = $stage
        $env:VUS = [string]$stage.VirtualUsers
        $env:DURATION = $stage.Duration
        $summaryPath = Join-Path $outputDirectory "k6-summary-stage-$($stage.VirtualUsers).json"
        $k6OutputPath = Join-Path $outputDirectory "k6-stage-$($stage.VirtualUsers).log"
        $k6ErrorPath = Join-Path $outputDirectory "k6-stage-$($stage.VirtualUsers)-error.log"
        if ($SshTarget) {
            $remoteSummaryPath = "$remoteDirectory/k6-summary-stage-$($stage.VirtualUsers).json"
            $remoteOutputPath = "$remoteDirectory/k6-stage-$($stage.VirtualUsers).log"
            $remoteErrorPath = "$remoteDirectory/k6-stage-$($stage.VirtualUsers)-error.log"
            $remoteExitPath = "$remoteDirectory/k6-stage-$($stage.VirtualUsers).exit"
            $remotePidPath = "$remoteDirectory/k6-stage-$($stage.VirtualUsers).pid"
            $remoteEnvironmentPath = "$remoteDirectory/k6.env"
            $remoteEnvironment = "AUTH_TOKEN='$($userToken.access_token)'"
            $remoteEnvironment | ssh $SshTarget "umask 077; tr -d '\015' > '$remoteEnvironmentPath'"
            if ($LASTEXITCODE -ne 0) {
                throw "Unable to pass the temporary test token to the remote k6 runner."
            }
            ssh $SshTarget "chmod 600 '$remoteEnvironmentPath'"
            if ($LASTEXITCODE -ne 0) {
                throw "Unable to restrict permissions on the temporary remote k6 environment file."
            }
            $remoteRun = "cd '$remoteDirectory'; setsid ./run-k6-remote.sh './k6-v2.3.0-linux-amd64/k6' '$($stage.VirtualUsers)' '$($stage.Duration)' 'http://127.0.0.1:$remoteGatewayPort' '$remoteSummaryPath' '$remoteOutputPath' '$remoteErrorPath' '$remoteExitPath' >/dev/null 2>&1 </dev/null & echo `$! > '$remotePidPath'"
            ssh $SshTarget $remoteRun
            if ($LASTEXITCODE -ne 0) {
                throw "Unable to start the $($stage.VirtualUsers)-VU k6 stage on the development server."
            }
            $k6Started = $true
            $k6ExitCode = $null
        } else {
            $k6Process = Start-Process -FilePath $k6Path `
                -ArgumentList @("run", "--summary-export", $summaryPath, "tests/performance/gateway-platform.js") `
                -PassThru -RedirectStandardOutput $k6OutputPath -RedirectStandardError $k6ErrorPath
        }
        while ($SshTarget -and $null -eq $k6ExitCode -or (-not $SshTarget -and -not $k6Process.HasExited)) {
            $timestamp = [DateTimeOffset]::Now.ToString("o")
            if ($SshTarget) {
                $sampleStartedAt = [Diagnostics.Stopwatch]::StartNew()
                $remoteSampleCommand = "if [ -f '$remoteExitPath' ]; then printf 'k6-state='; cat '$remoteExitPath'; else printf 'k6-state=running\n'; fi; docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' '$keycloakName' '$gatewayName' '$accountName'; printf 'k6-load-generator,'; ps -C k6 -o %cpu=,rss= | head -n 1"
                $sampleOutput = ssh $SshTarget $remoteSampleCommand
                if ($LASTEXITCODE -ne 0) {
                    throw "Unable to sample the remote $($stage.VirtualUsers)-VU load stage."
                }
                $remoteState = $sampleOutput | Where-Object { $_ -like "k6-state=*" } | Select-Object -First 1
                $remoteState = ([string]$remoteState).Substring("k6-state=".Length).Trim()
                if ($remoteState -ne "running") {
                    $k6ExitCode = [int]$remoteState
                }
                foreach ($sample in $sampleOutput | Where-Object { $_ -match "^(onecard-phase1-(?:keycloak|gateway|account)-$suffix)," }) {
                    "$timestamp,$sample" | Add-Content -LiteralPath $resourcePath
                }
                $loadGenerator = $sampleOutput | Where-Object { $_ -like "k6-load-generator,*" } | Select-Object -First 1
                if ([string]$loadGenerator -match '^k6-load-generator,\s*(?<cpu>[\d.]+)\s+(?<rss>\d+)') {
                    $memoryMiB = [math]::Round(([double]$Matches.rss / 1024), 1)
                    "$timestamp,k6-load-generator,$($Matches.cpu),$($memoryMiB)MiB" | Add-Content -LiteralPath $resourcePath
                }
                $sampleStartedAt.Stop()
            }
            if (-not $SshTarget) {
                $samples = docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' `
                    $keycloakName $gatewayName $accountName 2>$null
                foreach ($sample in $samples) {
                    "$timestamp,$sample" | Add-Content -LiteralPath $resourcePath
                }
                $k6Process.Refresh()
                Start-Sleep -Seconds 5
            } else {
                $remainingDelay = [math]::Max(0, 5000 - [int]$sampleStartedAt.ElapsedMilliseconds)
                if ($remainingDelay -gt 0 -and $null -eq $k6ExitCode) { Start-Sleep -Milliseconds $remainingDelay }
            }
        }
        if ($SshTarget) {
            foreach ($file in @(
                @{ Remote = $remoteSummaryPath; Local = $summaryPath },
                @{ Remote = $remoteOutputPath; Local = $k6OutputPath },
                @{ Remote = $remoteErrorPath; Local = $k6ErrorPath }
            )) {
                scp "$($SshTarget):$($file.Remote)" $file.Local | Out-Null
                if ($LASTEXITCODE -ne 0) {
                    throw "Unable to retrieve the $($stage.VirtualUsers)-VU k6 evidence from the development server."
                }
            }
            $k6Started = $false
        } else {
            $k6Process.WaitForExit()
            $k6ExitCode = $k6Process.ExitCode
        }
        if (-not (Test-Path -LiteralPath $summaryPath)) {
            throw "The Gateway k6 $($stage.VirtualUsers)-VU stage did not produce a summary; see $k6OutputPath and $k6ErrorPath."
        }
        foreach ($k6LogPath in @($k6OutputPath, $k6ErrorPath)) {
            if (Test-Path -LiteralPath $k6LogPath) {
                $k6LogReview = Review-TestLog (Get-Content -LiteralPath $k6LogPath -Raw)
                $k6LogReview.Text | Set-Content -LiteralPath $k6LogPath
                if ($k6LogReview.ContainsCredentials) {
                    throw "A temporary token appeared in k6 output; the saved logs were redacted."
                }
            }
        }
        $k6Summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
        if ($k6ExitCode -ne 0) {
            throw "The Gateway k6 $($stage.VirtualUsers)-VU stage failed its thresholds; see $k6OutputPath and $k6ErrorPath."
        }
        $stageResults.Add([pscustomobject]@{
            VirtualUsers = $stage.VirtualUsers
            Duration = $stage.Duration
            Requests = [int]$k6Summary.metrics.http_reqs.count
            AchievedRequestsPerMinute = [math]::Round($k6Summary.metrics.http_reqs.rate * 60, 1)
            FailedRequestRatePercent = [math]::Round($k6Summary.metrics.http_req_failed.value * 100, 3)
            CheckPasses = [int]$k6Summary.metrics.checks.passes
            CheckFailures = [int]$k6Summary.metrics.checks.fails
            P50Milliseconds = [math]::Round($k6Summary.metrics.http_req_duration.'p(50)', 1)
            P95Milliseconds = [math]::Round($k6Summary.metrics.http_req_duration.'p(95)', 1)
            P99Milliseconds = [math]::Round($k6Summary.metrics.http_req_duration.'p(99)', 1)
            Http200 = if ($k6Summary.metrics.gateway_http_200) { [int]$k6Summary.metrics.gateway_http_200.count } else { 0 }
            Non200 = if ($k6Summary.metrics.gateway_http_non_200) { [int]$k6Summary.metrics.gateway_http_non_200.count } else { 0 }
        })
    }
    $stageResults | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outputDirectory "k6-stages.json")

    $logLeakFound = $false
    foreach ($containerName in @($keycloakName, $gatewayName, $accountName)) {
        $containerOutput = docker logs $containerName 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to capture logs for $containerName."
        }
        $containerLogReview = Review-TestLog $containerOutput
        $containerLogReview.Text | Set-Content -LiteralPath (Join-Path $outputDirectory "$containerName.log")
        $logLeakFound = $logLeakFound -or $containerLogReview.ContainsCredentials
    }
    if ($logLeakFound) {
        throw "A temporary token or secret appeared in service logs; redacted copies were saved under $outputDirectory."
    }

    $prometheus = Get-HttpResponse "http://127.0.0.1:$localManagementPort/actuator/prometheus" "" ""
    if ($prometheus.StatusCode -ne 200) {
        throw "Unable to capture Gateway JVM and process metrics."
    }
    $prometheus.Body | Set-Content -LiteralPath (Join-Path $outputDirectory "gateway-prometheus.txt")
    $resourceSamples = Import-Csv -LiteralPath $resourcePath
    $resourceNames = @($keycloakName, $gatewayName, $accountName)
    if ($SshTarget) { $resourceNames += "k6-load-generator" }
    $resourceSummary = foreach ($containerName in $resourceNames) {
        $containerSamples = @($resourceSamples | Where-Object { $_.name -eq $containerName })
        $cpuSamples = @($containerSamples | ForEach-Object { [double]$_.cpu.TrimEnd("%") })
        $memorySamplesMb = @($containerSamples | ForEach-Object {
            if ($_.memory -match "(?<amount>[\d.]+)(?<unit>MiB|GiB)") {
                $amount = [double]$Matches.amount
                if ($Matches.unit -eq "GiB") { $amount * 1024 } else { $amount }
            }
        })
        [pscustomobject]@{
            Name = $containerName
            MaxCpuPercent = if ($cpuSamples.Count) { [math]::Round(($cpuSamples | Measure-Object -Maximum).Maximum, 2) } else { 0 }
            MaxMemoryMiB = if ($memorySamplesMb.Count) { [math]::Round(($memorySamplesMb | Measure-Object -Maximum).Maximum, 1) } else { 0 }
        }
    }
    $gcCount = 0.0
    $gcPauseSeconds = 0.0
    foreach ($match in [regex]::Matches($prometheus.Body, "(?m)^jvm_gc_pause_seconds_count\{[^}]*\}\s+(?<value>[\d.Ee+-]+)$")) {
        $gcCount += [double]::Parse($match.Groups["value"].Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    foreach ($match in [regex]::Matches($prometheus.Body, "(?m)^jvm_gc_pause_seconds_sum\{[^}]*\}\s+(?<value>[\d.Ee+-]+)$")) {
        $gcPauseSeconds += [double]::Parse($match.Groups["value"].Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    $heapUsedBytes = 0.0
    foreach ($match in [regex]::Matches($prometheus.Body, '(?m)^jvm_memory_used_bytes\{area="heap"[^}]*\}\s+(?<value>[\d.Ee+-]+)$')) {
        $heapUsedBytes += [double]::Parse($match.Groups["value"].Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    $executorActive = [regex]::Match($prometheus.Body, "(?m)^executor_active_threads\{[^}]*\}\s+(?<value>[\d.Ee+-]+)$").Groups["value"].Value
    $executorQueued = [regex]::Match($prometheus.Body, "(?m)^executor_queued_tasks\{[^}]*\}\s+(?<value>[\d.Ee+-]+)$").Groups["value"].Value
    $processCpu = [regex]::Match($prometheus.Body, "(?m)^process_cpu_usage\s+(?<value>[\d.Ee+-]+)$").Groups["value"].Value
    $totalRequests = ($stageResults | Measure-Object -Property Requests -Sum).Sum
    $totalHttp200 = ($stageResults | Measure-Object -Property Http200 -Sum).Sum
    $totalNon200 = ($stageResults | Measure-Object -Property Non200 -Sum).Sum
    $totalChecksPassed = ($stageResults | Measure-Object -Property CheckPasses -Sum).Sum
    $totalChecksFailed = ($stageResults | Measure-Object -Property CheckFailures -Sum).Sum
    $report = [System.Collections.Generic.List[string]]::new()
    $report.Add("# Phase 1 Gateway Platform Smoke")
    $report.Add("")
    $report.Add("- Target: admin1@192.168.0.41")
    $report.Add("- Keycloak: 26.8.1; issuer, audience, signature, role and service scope checked with real tokens.")
    $report.Add("- Scenario: authenticated Gateway to isolated stub at a paced 3,200 requests/minute across 50/100/250/500 VU stages; the 500-VU stage runs for $Duration.")
    $report.Add("- Account service authenticated-scope probe returned HTTP 200; missing-scope probe returned HTTP 403.")
    foreach ($result in $stageResults) {
        $report.Add("- Stage $($result.VirtualUsers) VUs ($($result.Duration)): $($result.Requests) requests, $($result.AchievedRequestsPerMinute)/min, p50/p95/p99=$($result.P50Milliseconds)/$($result.P95Milliseconds)/$($result.P99Milliseconds) ms, HTTP errors $($result.FailedRequestRatePercent)%, checks $($result.CheckPasses)/$($result.CheckPasses + $result.CheckFailures).")
    }
    $report.Add("- Total requests: $totalRequests; HTTP status counts: 200=$totalHttp200; non-200=$totalNon200.")
    $report.Add("- Total functional checks: $totalChecksPassed/$($totalChecksPassed + $totalChecksFailed).")
    $report.Add("- Peak container and load-generator resources (sampled approximately every 5s): $(($resourceSummary | ForEach-Object { "$($_.Name) CPU $($_.MaxCpuPercent)% / $($_.MaxMemoryMiB) MiB" }) -join '; ').")
    $report.Add("- Gateway JVM final scrape: process CPU $([math]::Round(([double]::Parse($processCpu, [Globalization.CultureInfo]::InvariantCulture) * 100), 2))%; heap used $([math]::Round($heapUsedBytes / 1MB, 1)) MiB; GC $gcCount collections / $([math]::Round($gcPauseSeconds, 3)) s cumulative pause; executor active=$executorActive, queued=$executorQueued.")
    $report.Add("- Database connection pool: not applicable to this Gateway-to-stub route; no database is in the request path.")
    $report.Add("- Application, Keycloak and integration-test logs were scanned for temporary credentials; no leak found.")
    $report.Add("- Enforced development test thresholds: HTTP errors <1%, p95 <500 ms, checks >99%; this does not establish production SLOs.")
    $report.Add("- User token and client secret are not included in this report.")
    $report.Add("- Raw per-stage k6, Prometheus and container samples are in this directory.")
    $report | Set-Content -LiteralPath (Join-Path $outputDirectory "report.md")
    Write-Host "OIDC and Gateway platform smoke passed. Evidence: $outputDirectory"
} catch {
    if ($summaryPath -and (Test-Path -LiteralPath $summaryPath)) {
        $failedSummary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
        @(
            "# Phase 1 Gateway Platform Smoke (failed)",
            "",
            "- Failed stage: $($activeStage.VirtualUsers) VUs for $($activeStage.Duration); target pacing is 3,200 requests/minute.",
            "- Requests: $($failedSummary.metrics.http_reqs.count); achieved rate: $([math]::Round($failedSummary.metrics.http_reqs.rate * 60, 1))/minute.",
            "- Latency: p50=$([math]::Round($failedSummary.metrics.http_req_duration.'p(50)', 1)) ms, p95=$([math]::Round($failedSummary.metrics.http_req_duration.'p(95)', 1)) ms, p99=$([math]::Round($failedSummary.metrics.http_req_duration.'p(99)', 1)) ms.",
            "- Failed HTTP requests: $([math]::Round($failedSummary.metrics.http_req_failed.value * 100, 3))%; functional checks: $($failedSummary.metrics.checks.passes)/$($failedSummary.metrics.checks.passes + $failedSummary.metrics.checks.fails).",
            "- HTTP status counts: 200=$($failedSummary.metrics.gateway_http_200.count); non-200=$($failedSummary.metrics.gateway_http_non_200.count).",
            "- k6 response checks: $($failedSummary.root_group.checks.'gateway returns stub response'.passes) stub responses passed; $($failedSummary.root_group.checks.'gateway returns W3C trace context'.passes) response trace headers passed; $($failedSummary.root_group.checks.'gateway propagates W3C trace context upstream'.passes) upstream trace checks passed.",
            "- This run failed; raw k6 and service logs are retained in this directory."
        ) | Set-Content -LiteralPath (Join-Path $outputDirectory "report.md")
    }
    if ($dockerContextCreated -and (Test-Path -LiteralPath $outputDirectory)) {
        foreach ($containerName in @(
            @{ Name = $keycloakName; Created = $keycloakCreated },
            @{ Name = $gatewayName; Created = $gatewayCreated },
            @{ Name = $accountName; Created = $accountCreated }
        )) {
            if ($containerName.Created) {
                $containerOutput = docker logs $containerName.Name 2>&1 | Out-String
                $containerLogReview = Review-TestLog $containerOutput
                $containerLogReview.Text |
                    Set-Content -LiteralPath (Join-Path $outputDirectory "$($containerName.Name).log")
            }
        }
    }
    throw
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
    $httpClient.Dispose()
    if ($keycloakCreated) { docker rm --force $keycloakName 2>$null | Out-Null }
    if ($gatewayCreated) { docker rm --force $gatewayName 2>$null | Out-Null }
    if ($accountCreated) { docker rm --force $accountName 2>$null | Out-Null }
    if ($sshTunnel -and -not $sshTunnel.HasExited) { Stop-Process -Id $sshTunnel.Id -Force }
    if ($SshTarget -and $remoteDirectory -match '^/tmp/onecard-phase1-[a-f0-9]{12}$') {
        $remoteCleanup = ""
        foreach ($stageUsers in @(500, 250, 100, 50)) {
            $remotePidFile = "$remoteDirectory/k6-stage-$stageUsers.pid"
            $remoteCleanup += "if [ -f '$remotePidFile' ]; then kill -- -`$(cat '$remotePidFile') 2>/dev/null || true; fi; "
        }
        if ([string]$stubPid -match '^\d+$') { $remoteCleanup += "kill '$stubPid' 2>/dev/null || true; " }
        $remoteCleanup += "rm -rf -- '$remoteDirectory'"
        ssh $SshTarget $remoteCleanup | Out-Null
    }
    if (Test-Path -LiteralPath $k6ArchivePath) { Remove-Item -LiteralPath $k6ArchivePath -Force }
    docker image rm --force $gatewayImage $accountImage 2>$null | Out-Null
    $env:DOCKER_CONTEXT = $savedDockerContext
    if ($dockerContextCreated) { docker context rm --force $contextName | Out-Null }
    if ($adminPassword) { $adminPassword = "" }
    if ($adminToken) { $adminToken = $null }
    if ($masterToken) { $masterToken = $null }
    if ($userPassword) { $userPassword = "" }
    if ($serviceClientSecret) { $serviceClientSecret = "" }
    if ($userToken) { $userToken = $null }
    if ($serviceToken) { $serviceToken = $null }
    if ($scopeMissingToken) { $scopeMissingToken = $null }
}
