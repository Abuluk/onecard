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
Add-Type -AssemblyName System.Net.Http
$httpClient = [System.Net.Http.HttpClient]::new()
$savedLocation = (Get-Location).Path
$savedDockerContext = $env:DOCKER_CONTEXT
$savedNacosToken = [Environment]::GetEnvironmentVariable("NACOS_AUTH_TOKEN", "Process")
$outputDirectory = Join-Path $repositoryRoot "artifacts/platform-recovery-$suffix"

function Invoke-Compose([string[]] $Arguments, [int] $Parallel = -1) {
    $parallelOptions = @()
    if ($Parallel -gt 0) {
        $parallelOptions = @("--parallel", "$Parallel")
    }
    & docker compose --project-name $project --file $composeFile @parallelOptions @Arguments
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

function Assert-GatewayPortBinding {
    $containerId = & docker compose --project-name $project --file $composeFile ps --quiet onecard-gateway
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerId)) {
        throw "Unable to find the Gateway container for its published-port check."
    }
    $portBindingsJson = & docker inspect --format '{{json .NetworkSettings.Ports}}' $containerId
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect the Gateway container's published ports."
    }
    $portBindings = ($portBindingsJson -join "") | ConvertFrom-Json
    $binding = @($portBindings.'8081/tcp') | Select-Object -First 1
    if (-not $binding -or $binding.HostIp -ne "127.0.0.1" -or $binding.HostPort -ne "8081") {
        throw "Gateway host port 8081 is not bound to 127.0.0.1 through the ingress network."
    }
    if ($SshTarget -match "^[^@]+@(?<host>.+)$" -and
        (Test-NetConnection -ComputerName $Matches.host -Port 8081 -InformationLevel Quiet -WarningAction SilentlyContinue)) {
        throw "Gateway port 8081 is reachable outside the target host's loopback interface."
    }
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
    Invoke-Compose ((@("build", "--pull=false") + $applicationServices)) 1

    $stackAttempted = $true
    Invoke-Compose @("up", "--no-build", "--detach", "--wait", "--wait-timeout", "300", "onecard-gateway")
    Assert-GatewayPortBinding
    foreach ($service in $servicePorts) {
        Wait-NacosRegistration $service.Name $service.Port
    }

    Invoke-Compose (@("stop") + $applicationServices)
    Invoke-Compose (@(
        "up", "--no-build", "--detach", "--wait", "--wait-timeout", "300"
    ) + $applicationServices)
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
        "- Gateway host port 8081 is published only on 127.0.0.1 through the onecard-ingress network.",
        "- Direct TCP access from the smoke runner to the target host's port 8081 was unavailable.",
        "- All nine application containers were stopped and restarted; healthy Nacos registrations recovered: $($servicePorts.Count)/$($servicePorts.Count).",
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

        $status = & docker compose --project-name $project --file $composeFile ps --all 2>&1 | Out-String
        $status | Set-Content -LiteralPath (Join-Path $outputDirectory "platform-failure-status.txt")
        $containerIds = @(& docker compose --project-name $project --file $composeFile ps --all --quiet)
        if ($containerIds.Count -gt 0) {
            & docker inspect --format '{{json .State}}' @containerIds |
                Set-Content -LiteralPath (Join-Path $outputDirectory "platform-failure-container-state.jsonl")
        }
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
