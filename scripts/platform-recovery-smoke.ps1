param(
    [string] $DockerHost = "ssh://admin1@192.168.0.41",
    [string] $SshTarget = "admin1@192.168.0.41"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 10)
$project = "onecard-recovery-$suffix"
$contextName = "$project-context"
$composeFile = "deploy/monitoring/docker-compose.yml"
$servicePorts = @(
    @{ Name = "onecard-gateway"; Port = 8081 },
    @{ Name = "onecard-account-service"; Port = 8082 },
    @{ Name = "onecard-transaction-service"; Port = 8083 },
    @{ Name = "onecard-payment-adapter-service"; Port = 8084 },
    @{ Name = "onecard-clearing-service"; Port = 8085 },
    @{ Name = "onecard-notification-service"; Port = 8086 },
    @{ Name = "onecard-risk-service"; Port = 8087 },
    @{ Name = "onecard-access-service"; Port = 8088 },
    @{ Name = "onecard-device-service"; Port = 8089 }
)
$dockerContextCreated = $false
$stackAttempted = $false
$sshTunnel = $null
$httpClient = [System.Net.Http.HttpClient]::new()
$savedLocation = (Get-Location).Path
$savedDockerContext = $env:DOCKER_CONTEXT
$savedNacosToken = [Environment]::GetEnvironmentVariable("NACOS_AUTH_TOKEN", "Process")
$outputDirectory = Join-Path $repositoryRoot "artifacts/platform-recovery-$suffix"

function Invoke-Compose([string[]] $Arguments) {
    & docker compose --project-name $project --file $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed."
    }
}

function Test-NacosRegistration([string] $ServiceName, [int] $Port) {
    $url = "http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=$ServiceName&groupName=DEFAULT_GROUP"
    $response = & docker compose --project-name $project --file $composeFile exec -T nacos `
        curl --fail --silent --show-error $url 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $false
    }
    try {
        $instances = ($response -join "") | ConvertFrom-Json
        return [bool](@($instances.data) | Where-Object {
            $_.healthy -eq $true -and $_.enabled -eq $true -and $_.port -eq $Port
        } | Select-Object -First 1)
    } catch {
        return $false
    }
}

function Wait-NacosRegistration([string] $ServiceName, [int] $Port) {
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        if (Test-NacosRegistration $ServiceName $Port) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$ServiceName did not register as a healthy Nacos instance on port $Port."
}

function Get-FreeLocalPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

try {
    if ([bool]$DockerHost -ne [bool]$SshTarget) {
        throw "DockerHost and SshTarget must be provided together."
    }
    Set-Location $repositoryRoot
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

    if ($DockerHost) {
        & docker context create $contextName --docker "host=$DockerHost" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to create the temporary Docker context."
        }
        $dockerContextCreated = $true
        $env:DOCKER_CONTEXT = $contextName
    }
    if ([string]::IsNullOrWhiteSpace($env:NACOS_AUTH_TOKEN) -or
        $env:NACOS_AUTH_TOKEN -eq "REPLACE_WITH_BASE64_ENCODED_32_BYTE_SECRET") {
        $env:NACOS_AUTH_TOKEN = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    }

    & .\mvnw.cmd --batch-mode --no-transfer-progress clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "The Maven clean verify gate failed."
    }

    $applicationServices = @($servicePorts | ForEach-Object { $_.Name })
    Invoke-Compose (@("build", "--pull=false") + $applicationServices)

    $stackAttempted = $true
    Invoke-Compose @("up", "--no-build", "--detach", "--wait", "--wait-timeout", "300", "onecard-gateway")
    foreach ($service in $servicePorts) {
        Wait-NacosRegistration $service.Name $service.Port
    }

    Invoke-Compose @("restart", "nacos")
    Invoke-Compose @("up", "--no-build", "--detach", "--wait", "--wait-timeout", "180", "nacos")
    foreach ($service in $servicePorts) {
        Wait-NacosRegistration $service.Name $service.Port
    }

    Invoke-Compose @("restart", "onecard-account-service")
    Invoke-Compose @("up", "--no-build", "--detach", "--wait", "--wait-timeout", "180", "onecard-account-service")
    Wait-NacosRegistration "onecard-account-service" 8082

    $gatewayPort = 8081
    if ($SshTarget) {
        $gatewayPort = Get-FreeLocalPort
        $sshTunnel = Start-Process -FilePath "ssh" -ArgumentList @(
            "-N",
            "-L", "127.0.0.1:${gatewayPort}:127.0.0.1:8081",
            "-o", "ExitOnForwardFailure=yes",
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=10",
            $SshTarget
        ) -PassThru -WindowStyle Hidden
        $tunnelReady = $false
        for ($attempt = 1; $attempt -le 100; $attempt++) {
            if ($sshTunnel.HasExited) {
                throw "The SSH tunnel exited before it became ready."
            }
            if (Get-NetTCPConnection -State Listen -LocalPort $gatewayPort -ErrorAction SilentlyContinue) {
                $tunnelReady = $true
                break
            }
            Start-Sleep -Milliseconds 100
        }
        if (-not $tunnelReady) {
            throw "The Gateway SSH tunnel did not become ready."
        }
    }

    $response = $httpClient.GetAsync("http://127.0.0.1:$gatewayPort/api/account/balance").GetAwaiter().GetResult()
    $statusCode = [int]$response.StatusCode
    $response.Dispose()
    if ($statusCode -ne 401) {
        throw "The Gateway returned HTTP $statusCode for an anonymous protected API request; expected 401."
    }

    @(
        "# Phase 1 Platform Recovery Smoke",
        "",
        "- Target: $(if ($SshTarget) { $SshTarget } else { 'local Docker engine' })",
        "- Healthy Nacos registrations verified before and after Nacos restart: $($servicePorts.Count)/$($servicePorts.Count).",
        "- Account service restart returned a healthy Nacos registration.",
        "- Anonymous protected Gateway request returned HTTP 401.",
        "- Temporary Compose project, volumes, images, Docker context, and SSH tunnel are removed by the script."
    ) | Set-Content -LiteralPath (Join-Path $outputDirectory "report.md")
    Write-Host "Phase 1 full platform recovery smoke passed. Evidence: $outputDirectory"
} catch {
    if ($stackAttempted) {
        $logs = & docker compose --project-name $project --file $composeFile logs --no-color 2>&1 | Out-String
        if ($env:NACOS_AUTH_TOKEN) {
            $logs = $logs.Replace($env:NACOS_AUTH_TOKEN, "[REDACTED]")
        }
        $logs | Set-Content -LiteralPath (Join-Path $outputDirectory "platform-failure.log")
    }
    throw
} finally {
    $httpClient.Dispose()
    if ($sshTunnel -and -not $sshTunnel.HasExited) {
        Stop-Process -Id $sshTunnel.Id -Force
    }
    if ($stackAttempted) {
        & docker compose --project-name $project --file $composeFile down --volumes --remove-orphans 2>$null | Out-Null
        foreach ($service in $applicationServices) {
            & docker image rm --force "${project}-${service}:latest" 2>$null | Out-Null
        }
    }
    if ($dockerContextCreated) {
        & docker context rm --force $contextName | Out-Null
    }
    $env:DOCKER_CONTEXT = $savedDockerContext
    [Environment]::SetEnvironmentVariable("NACOS_AUTH_TOKEN", $savedNacosToken, "Process")
    Set-Location $savedLocation
}
