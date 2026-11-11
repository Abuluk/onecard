param(
    [string] $DockerHost,
    [string] $SshTarget
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path $PSScriptRoot -Parent
$contextName = "onecard-nacos-refresh-context-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$project = "onecard-nacos-refresh-$([guid]::NewGuid().ToString('N').Substring(0, 10))"
$dataId = "onecard-account-service.yaml"
$group = "DEFAULT_GROUP"
$savedDockerContext = $env:DOCKER_CONTEXT
$savedNacosToken = [Environment]::GetEnvironmentVariable("NACOS_AUTH_TOKEN", "Process")
$savedProbeEnabled = [Environment]::GetEnvironmentVariable("ONECARD_DEV_CONFIG_PROBE_ENABLED", "Process")
$dockerContextCreated = $false
$stackAttempted = $false
$imageBuilt = $false
$configPublished = $false
$composeFile = "deploy/monitoring/docker-compose.yml"
$composeImage = "${project}-onecard-account-service:latest"

function Publish-TestConfig([string] $value) {
    $content = "onecard:`n  dev:`n    config-probe:`n      value: $value`n"
    $response = docker compose -p $project -f $composeFile exec -T nacos `
        curl --fail --silent --show-error -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" `
        --data-urlencode "dataId=$dataId" `
        --data-urlencode "group=$group" `
        --data-urlencode "content=$content"
    if ($LASTEXITCODE -ne 0 -or $response -ne "true") {
        throw "Nacos config publish failed: $response"
    }
}

function Get-ProbeValue {
    $response = docker compose -p $project -f $composeFile exec -T onecard-account-service `
        wget -q -O - "http://127.0.0.1:8082/internal/dev/config-probe" 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    try {
        return ($response | ConvertFrom-Json).value
    } catch {
        return $null
    }
}

try {
    if ([bool]$DockerHost -ne [bool]$SshTarget) {
        throw "DockerHost and SshTarget must be provided together."
    }
    Set-Location $repositoryRoot
    if ($DockerHost) {
        docker context create $contextName --docker "host=$DockerHost" | Out-Null
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
    $env:ONECARD_DEV_CONFIG_PROBE_ENABLED = "true"

    & .\mvnw.cmd --batch-mode --no-transfer-progress `
        -pl services/onecard-account-service -am package
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to build the account service image artifact."
    }

    docker compose -p $project -f $composeFile build --pull=false onecard-account-service
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to build the account service container image."
    }
    $imageBuilt = $true

    $stackAttempted = $true
    docker compose -p $project -f $composeFile up --no-build --detach --wait --wait-timeout 180 nacos
    if ($LASTEXITCODE -ne 0) {
        throw "Nacos did not become healthy."
    }

    $alpha = "alpha-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $beta = "beta-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $configPublished = $true
    Publish-TestConfig $alpha

    docker compose -p $project -f $composeFile up --no-build --detach --wait --wait-timeout 180 onecard-account-service
    if ($LASTEXITCODE -ne 0) {
        throw "The account service did not become healthy."
    }
    if ((Get-ProbeValue) -ne $alpha) {
        throw "The account service did not import the initial Nacos value."
    }
    Write-Host "Initial Nacos value imported: $alpha"

    Publish-TestConfig $beta
    $refreshed = $false
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        if ((Get-ProbeValue) -eq $beta) {
            $refreshed = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $refreshed) {
        throw "The account service did not observe the Nacos update within 60 seconds."
    }
    Write-Host "Nacos refresh applied without restart after $attempt second(s): $beta"
} finally {
    if ($configPublished) {
        docker compose -p $project -f $composeFile exec -T nacos `
            curl --silent --show-error -X DELETE "http://127.0.0.1:8848/nacos/v1/cs/configs" `
            --data-urlencode "dataId=$dataId" `
            --data-urlencode "group=$group" | Out-Null
    }
    if ($stackAttempted) {
        docker compose -p $project -f $composeFile down --volumes --remove-orphans
    }
    if ($imageBuilt) {
        docker image rm $composeImage 2>$null | Out-Null
    }
    $env:DOCKER_CONTEXT = $savedDockerContext
    [Environment]::SetEnvironmentVariable("NACOS_AUTH_TOKEN", $savedNacosToken, "Process")
    [Environment]::SetEnvironmentVariable("ONECARD_DEV_CONFIG_PROBE_ENABLED", $savedProbeEnabled, "Process")
    if ($dockerContextCreated) {
        docker context rm --force $contextName | Out-Null
    }
}
