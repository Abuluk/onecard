$ErrorActionPreference = "Stop"

$projectName = "onecard-local-ci-$PID"
$composeArgs = @(
    "-p", $projectName,
    "-f", "deploy/monitoring/docker-compose.yml",
    "-f", "deploy/monitoring/docker-compose.debug.yml"
)
$environmentNames = @("NACOS_AUTH_TOKEN", "ACCOUNT_DB_ENABLED", "TRANSACTION_DB_ENABLED")
$savedEnvironment = @{}
$composeStarted = $false

foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    if (Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue) {
        throw "Local port 8082 is already in use. Stop that process before running local CI."
    }

    Write-Host "=== Maven clean verify ===" -ForegroundColor Cyan
    .\mvnw.cmd --batch-mode --no-transfer-progress clean verify
    if ($LASTEXITCODE -ne 0) {
        throw "Maven clean verify failed."
    }

    $env:NACOS_AUTH_TOKEN = [Convert]::ToBase64String(
        [Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $env:ACCOUNT_DB_ENABLED = "false"
    $env:TRANSACTION_DB_ENABLED = "false"

    Write-Host "=== Start isolated Nacos and account service ===" -ForegroundColor Cyan
    $composeStarted = $true
    docker compose @composeArgs up --build --detach --wait --wait-timeout 180 onecard-account-service
    if ($LASTEXITCODE -ne 0) {
        throw "The local account service failed to start."
    }

    Write-Host "=== Verify Nacos service registration ===" -ForegroundColor Cyan
    $registrationUrl = "http://127.0.0.1:8848/nacos/v3/client/ns/instance/list?serviceName=onecard-account-service&groupName=DEFAULT_GROUP"
    $registration = docker compose @composeArgs exec -T nacos curl --fail --silent --show-error $registrationUrl
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to query Nacos for the account service instance."
    }
    $instances = ($registration | ConvertFrom-Json).data
    if (-not ($instances | Where-Object {
        $_.healthy -and $_.enabled -and $_.port -eq 8082 -and
        $_.metadata.'preserved.register.source' -eq "SPRING_CLOUD"
    })) {
        throw "Nacos did not report a healthy Spring Cloud account service on port 8082."
    }

    Write-Host "=== Verify Nacos restart recovery ===" -ForegroundColor Cyan
    docker compose @composeArgs restart nacos
    if ($LASTEXITCODE -ne 0) {
        throw "Nacos failed to restart."
    }
    docker compose @composeArgs up --detach --wait --wait-timeout 180 nacos
    if ($LASTEXITCODE -ne 0) {
        throw "Nacos did not become healthy after restart."
    }
    $registrationRecovered = $false
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        $registration = docker compose @composeArgs exec -T nacos curl --fail --silent $registrationUrl
        if ($LASTEXITCODE -eq 0) {
            $instances = ($registration | ConvertFrom-Json).data
            if ($instances | Where-Object {
                $_.healthy -and $_.enabled -and $_.port -eq 8082 -and
                $_.metadata.'preserved.register.source' -eq "SPRING_CLOUD"
            }) {
                $registrationRecovered = $true
                break
            }
        }
        Start-Sleep -Seconds 2
    }
    if (-not $registrationRecovered) {
        throw "The account service did not re-register with Nacos after restart."
    }

    Write-Host "=== Run account health smoke ===" -ForegroundColor Cyan
    k6 run --env BASE_URL=http://localhost:8082 tests/performance/smoke.js
    if ($LASTEXITCODE -ne 0) {
        throw "The account health smoke failed."
    }
}
finally {
    if ($composeStarted) {
        docker compose @composeArgs down --volumes --remove-orphans
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}

Write-Host "Local CI passed." -ForegroundColor Green
