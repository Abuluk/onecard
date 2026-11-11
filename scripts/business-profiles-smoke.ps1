param(
    [string] $DockerHost = "ssh://admin1@192.168.0.41",
    [string] $LocalDockerContext = "desktop-linux",
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
$uploadedImages = @()
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

function Wait-OceanBaseQuery {
    for ($attempt = 1; $attempt -le 120; $attempt++) {
        $query = & docker compose --project-name $project --file $composeFile exec -T onecard-oceanbase `
            obclient -h 127.0.0.1 -u root@sys -P 2881 --connect-timeout=3 -N -e "SELECT 1" 2>$null
        if ($LASTEXITCODE -eq 0 -and ($query -join "").Trim() -eq "1") {
            return [pscustomobject]@{
                Attempts = $attempt
                Value = ($query -join "").Trim()
            }
        }
        Start-Sleep -Seconds 2
    }
    throw "OceanBase did not return SELECT 1 within 120 query attempts after its container became healthy."
}

function Sync-PinnedImage([string] $ImageReference) {
    $separatorIndex = $ImageReference.LastIndexOf("@sha256:")
    if ($separatorIndex -lt 1) {
        throw "Expected a sha256-pinned image reference: $ImageReference"
    }
    $localTag = $ImageReference.Substring(0, $separatorIndex)
    $expectedDigest = $ImageReference.Substring($separatorIndex + 1)
    $remoteEvidence = & docker --context $contextName image inspect --format 'Id={{.Id}};RepoDigests={{json .RepoDigests}}' $ImageReference 2>$null
    if ($LASTEXITCODE -eq 0 -and ($remoteEvidence -join "") -match [regex]::Escape($expectedDigest)) {
        return $false
    }

    $localEvidence = & docker --context $LocalDockerContext image inspect --format 'Id={{.Id}};RepoDigests={{json .RepoDigests}}' $localTag 2>$null
    if ($LASTEXITCODE -ne 0 -or ($localEvidence -join "") -notmatch [regex]::Escape($expectedDigest)) {
        throw "Image $ImageReference is missing on the target, and local context $LocalDockerContext does not contain the matching digest."
    }
    $localImageId = (($localEvidence -join "") -split ";", 2)[0].Substring(3)
    $archivePath = Join-Path $env:TEMP "$project-image-$([guid]::NewGuid().ToString('N')).tar"
    try {
        & docker --context $LocalDockerContext image save --output $archivePath $localTag
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to export local image $localTag."
        }
        & docker --context $contextName image load --input $archivePath
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to upload image $localTag to the target Docker engine."
        }
        $remoteEvidence = & docker --context $contextName image inspect --format 'Id={{.Id}};RepoDigests={{json .RepoDigests}}' $localTag
        if ($LASTEXITCODE -ne 0 -or ($remoteEvidence -join "") -notmatch [regex]::Escape($expectedDigest)) {
            throw "Uploaded image $localTag does not expose the expected repository digest on the target."
        }
        $remoteImageId = (($remoteEvidence -join "") -split ";", 2)[0].Substring(3)
        if ($remoteImageId -ne $localImageId) {
            throw "Uploaded image $localTag has a different image ID on the target."
        }
        Write-Host "Loaded verified image $ImageReference from local Docker context $LocalDockerContext."
        return $true
    } finally {
        Remove-Item -LiteralPath $archivePath -Force -ErrorAction SilentlyContinue
    }
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

    $configArguments = @()
    foreach ($profile in $Profiles) {
        $configArguments += @("--profile", $profile)
    }
    $configArguments += @("config", "--format", "json")
    $composeConfigJson = & docker compose --project-name $project --file $composeFile @configArguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose config failed."
    }
    $composeConfig = ($composeConfigJson -join "`n") | ConvertFrom-Json
    $imageServices = if ($Profiles -contains "business-data") {
        @("onecard-oceanbase")
    } else {
        @()
    }
    if ($Profiles -contains "business-messaging") {
        $imageServices += "onecard-rocketmq-broker"
    }
    $composeImages = $imageServices | ForEach-Object { $composeConfig.services.$_.image } | Select-Object -Unique
    foreach ($imageReference in $composeImages) {
        if (Sync-PinnedImage $imageReference) {
            $uploadedImages += $imageReference
        }
    }
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
    if ($uploadedImages.Count -gt 0) {
        $evidence += "- Transferred and verified pinned images from local Docker context ${LocalDockerContext}: $($uploadedImages -join ', ')."
    } else {
        $evidence += "- Required pinned images were already present on the target Docker engine."
    }
    if ($Profiles -contains "business-data") {
        $oceanBaseContainer = Get-HealthyContainer "onecard-oceanbase"
        $query = Wait-OceanBaseQuery
        $evidence += "- OceanBase CE 4.4.2-lts container health: healthy; tenant `SELECT 1` returned $($query.Value) after $($query.Attempts) checks."
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
        $downArguments = @()
        foreach ($profile in $Profiles) {
            $downArguments += @("--profile", $profile)
        }
        $downArguments += @("down", "--volumes", "--remove-orphans")
        try {
            $cleanupOutput = & docker compose --project-name $project --file $composeFile @downArguments 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "Compose cleanup returned exit code $LASTEXITCODE."
            }
        } catch {
            Write-Warning "Compose cleanup failed: $_"
        }
    }
    $env:DOCKER_CONTEXT = $savedDockerContext
    if ($dockerContextCreated) {
        & docker context rm --force $contextName | Out-Null
    }
    [Environment]::SetEnvironmentVariable("NACOS_AUTH_TOKEN", $savedNacosToken, "Process")
    Set-Location $savedLocation
}
