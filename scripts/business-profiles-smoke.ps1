param(
    [string] $DockerHost = "ssh://admin1@192.168.0.41",
    [ValidateSet("business-data", "business-messaging")]
    [string[]] $Profiles = @("business-data", "business-messaging")
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
$project = "onecard-business-smoke-$suffix"
$contextName = "$project-context"
$composeFile = "deploy/monitoring/docker-compose.yml"
$dockerContextCreated = $false
$stackAttempted = $false
$savedLocation = (Get-Location).Path
$savedDockerContext = $env:DOCKER_CONTEXT
$savedNacosToken = [Environment]::GetEnvironmentVariable("NACOS_AUTH_TOKEN", "Process")
$outputDirectory = Join-Path $repositoryRoot "artifacts/business-profiles-$suffix"
$services = @()
if ($Profiles -contains "business-data") {
    $services += "onecard-oceanbase"
}
if ($Profiles -contains "business-messaging") {
    $services += "onecard-rocketmq-broker"
}

function Invoke-Compose([string[]] $Arguments) {
    & docker compose --project-name $project --file $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed."
    }
}

function Get-HealthyContainer([string] $ServiceName) {
    $containerId = & docker compose --project-name $project --file $composeFile ps --quiet $ServiceName
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($containerId -join ""))) {
        throw "$ServiceName did not create a container."
    }
    $health = & docker inspect --format '{{.State.Health.Status}}' ($containerId -join "")
    if ($LASTEXITCODE -ne 0 -or ($health -join "").Trim() -ne "healthy") {
        throw "$ServiceName did not reach a healthy state."
    }
    return ($containerId -join "").Trim()
}

try {
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

    Invoke-Compose @("config", "--quiet")
    $hostEvidence = if ($DockerHost -match '^ssh://(?<target>.+)$') {
        ssh -o BatchMode=yes $Matches.target "uname -a; nproc; free -h; docker version --format '{{.Server.Version}}'"
    } else {
        @("local Docker engine", (docker version --format '{{.Server.Version}}'))
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to capture the Docker host and capacity information."
    }
    $hostEvidence | Set-Content -LiteralPath (Join-Path $outputDirectory "environment.txt")

    $stackAttempted = $true
    $upArguments = @()
    foreach ($profile in $Profiles) {
        $upArguments += @("--profile", $profile)
    }
    $upArguments += @("up", "--detach", "--wait", "--wait-timeout", "900") + $services
    Invoke-Compose $upArguments

    $evidence = @(
        "# Phase 1 Business Platform Profiles Smoke",
        "",
        "- Target: $DockerHost",
        "- Profiles: $($Profiles -join ', ')"
    )
    if ($Profiles -contains "business-data") {
        $oceanBaseContainer = Get-HealthyContainer "onecard-oceanbase"
        $query = & docker compose --project-name $project --file $composeFile exec -T onecard-oceanbase `
            obclient -h 127.0.0.1 -u root@sys -P 2881 --connect-timeout=3 -N -e "SELECT 1"
        if ($LASTEXITCODE -ne 0 -or ($query -join "").Trim() -ne "1") {
            throw "The OceanBase health query did not return 1."
        }
        $evidence += "- OceanBase CE 4.4.2-lts container health: healthy; `SELECT 1` returned $($query -join '')."
        $evidence += "- OceanBase container ID: $oceanBaseContainer"
    }
    if ($Profiles -contains "business-messaging") {
        $nameServerContainer = Get-HealthyContainer "onecard-rocketmq-namesrv"
        $brokerContainer = Get-HealthyContainer "onecard-rocketmq-broker"
        $evidence += "- RocketMQ 5.3.1 nameserver and broker health: healthy."
        $evidence += "- RocketMQ container IDs: nameserver $nameServerContainer; broker $brokerContainer"
    }
    $evidence += "- All services ran in Compose project $project with unique volumes removed after the run."
    $evidence | Set-Content -LiteralPath (Join-Path $outputDirectory "report.md")
    Write-Host "Phase 1 business platform profiles smoke passed. Evidence: $outputDirectory"
} catch {
    if ($stackAttempted) {
        $logs = & docker compose --project-name $project --file $composeFile `
            logs --no-color @services 2>&1 | Out-String
        if ($env:NACOS_AUTH_TOKEN) {
            $logs = $logs.Replace($env:NACOS_AUTH_TOKEN, "[REDACTED]")
        }
        $logs = [regex]::Replace($logs, '(?im)(\b(?:password|secret|token)\s*[:=]\s*)\S+', '$1[REDACTED]')
        $logs = [regex]::Replace($logs, '(?i)(\bBearer\s+)[A-Za-z0-9._~+/-]+=*', '$1[REDACTED]')
        $logs | Set-Content -LiteralPath (Join-Path $outputDirectory "platform-failure.log")
    }
    throw
} finally {
    if ($stackAttempted) {
        & docker compose --project-name $project --file $composeFile down --volumes --remove-orphans 2>$null | Out-Null
    }
    $env:DOCKER_CONTEXT = $savedDockerContext
    if ($dockerContextCreated) {
        & docker context rm --force $contextName | Out-Null
    }
    [Environment]::SetEnvironmentVariable("NACOS_AUTH_TOKEN", $savedNacosToken, "Process")
    Set-Location $savedLocation
}
