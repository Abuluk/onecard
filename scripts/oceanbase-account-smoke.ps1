param(
    [string] $Image = "oceanbase/oceanbase-ce:4.4.2-lts@sha256:eaa2c3b3513fa21e4a658f197c1deb93c9d6b6eb742cfa5ef0f085f82b9cec54",
    [string] $DockerHost,
    [string] $SshTarget,
    [ValidateRange(1, 4)]
    [int] $CpuLimit = 4,
    [switch] $CiProfile
)

$ErrorActionPreference = "Stop"
if ($CiProfile) {
    $containerMemory = "6g"
    $oceanBaseMemoryLimit = "4G"
    $oceanBaseDatafileSize = "2G"
    $oceanBaseLogDiskSize = "4G"
    $tenantMaxCpu = 1
    $tenantMemorySize = "1G"
    $tenantLogDiskSize = "1G"
} else {
    $containerMemory = "10g"
    $oceanBaseMemoryLimit = "6G"
    $oceanBaseDatafileSize = "5G"
    $oceanBaseLogDiskSize = "5G"
    $tenantMaxCpu = 2
    $tenantMemorySize = "2G"
    $tenantLogDiskSize = "2G"
}
$containerName = "onecard-oceanbase-smoke-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$dockerContextName = "onecard-oceanbase-smoke-context-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$mavenWrapper = Join-Path $repositoryRoot "mvnw"
if (Test-Path -LiteralPath (Join-Path $repositoryRoot "mvnw.cmd")) {
    $mavenWrapper = Join-Path $repositoryRoot "mvnw.cmd"
}
$containerCreated = $false
$dockerContextCreated = $false
$sshTunnel = $null
$outputDirectory = Join-Path $repositoryRoot "artifacts/oceanbase-smoke-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_OUTPUT)) {
    Add-Content -LiteralPath $env:GITHUB_OUTPUT -Value "report-directory=$outputDirectory"
}
$savedDockerContext = $env:DOCKER_CONTEXT
$savedEnvironment = @{}
$testVariables = @(
    "ONECARD_DB_IT_ENABLED",
    "ONECARD_DB_IT_URL",
    "ONECARD_DB_IT_SCHEMA",
    "ONECARD_DB_IT_OTHER_SCHEMA",
    "ONECARD_DB_IT_APP_USER",
    "ONECARD_DB_IT_APP_PASSWORD",
    "ONECARD_DB_IT_MIGRATION_USER",
    "ONECARD_DB_IT_MIGRATION_PASSWORD",
    "ONECARD_DB_IT_ISOLATION_ENABLED",
    "ONECARD_TRANSACTION_DB_IT_URL",
    "ONECARD_TRANSACTION_DB_IT_SCHEMA",
    "ONECARD_TRANSACTION_DB_IT_OTHER_SCHEMA",
    "ONECARD_TRANSACTION_DB_IT_APP_USER",
    "ONECARD_TRANSACTION_DB_IT_APP_PASSWORD",
    "ONECARD_TRANSACTION_DB_IT_MIGRATION_USER",
    "ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD"
)

function New-TestPassword {
    $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Invoke-SecretScannedTest([string[]] $Arguments, [string] $LogName, [string] $FailureMessage) {
    $testOutput = & $mavenWrapper @Arguments 2>&1 | Out-String
    $testExitCode = $LASTEXITCODE
    $containsSecret = $false
    foreach ($secret in @(
        $accountAppPassword,
        $accountMigrationPassword,
        $transactionAppPassword,
        $transactionMigrationPassword
    )) {
        if ($testOutput.Contains($secret)) {
            $containsSecret = $true
            $testOutput = $testOutput.Replace($secret, "[REDACTED]")
        }
    }
    if ($testOutput -match '(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*') {
        $containsSecret = $true
        $testOutput = [regex]::Replace($testOutput, '(?i)(\bBearer\s+)[A-Za-z0-9._~+/-]+=*', '$1[REDACTED]')
    }
    if ($testOutput -match '(?i)\b(?:client[_-]?secret|password)\s*[:=]\s*\S+') {
        $containsSecret = $true
        $testOutput = [regex]::Replace($testOutput, '(?i)(\b(?:client[_-]?secret|password)\s*[:=]\s*)\S+', '$1[REDACTED]')
    }
    $testOutput | Set-Content -LiteralPath (Join-Path $outputDirectory $LogName)
    if ($containsSecret) {
        throw "Database integration test output contained a sensitive value; a redacted log was saved under $outputDirectory."
    }
    if ($testExitCode -ne 0) {
        throw $FailureMessage
    }
}

function Save-DatabaseFailureDiagnostics {
    if (-not $containerCreated) {
        return
    }
    $diagnostics = @(
        "Container state:",
        (docker inspect --format '{{.State.Status}}' $containerName 2>&1 | Out-String),
        "Container resource snapshot:",
        (docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' $containerName 2>&1 | Out-String),
        "Tenant state:",
        (docker exec $containerName obclient -h 127.0.0.1 -u root@sys -P 2881 --connect-timeout=3 -N -e "SELECT STATUS FROM oceanbase.DBA_OB_TENANTS WHERE TENANT_NAME = 'onecard'" 2>&1 | Out-String),
        "Recent database logs:",
        (docker logs --tail 400 $containerName 2>&1 | Out-String)
    ) -join "`n"
    foreach ($secret in @(
        $accountAppPassword,
        $accountMigrationPassword,
        $transactionAppPassword,
        $transactionMigrationPassword
    )) {
        if ($secret) {
            $diagnostics = $diagnostics.Replace($secret, "[REDACTED]")
        }
    }
    $diagnostics = [regex]::Replace(
        $diagnostics,
        '(?i)(\b(?:client[_-]?secret|password)\s*[:=]\s*)\S+',
        '$1[REDACTED]')
    if ($SshTarget) {
        $diagnostics += "`nDevelopment host snapshot:`n" + (ssh $SshTarget "free -h; uptime" 2>&1 | Out-String)
    }
    $diagnostics | Set-Content -LiteralPath (Join-Path $outputDirectory "database-failure-diagnostics.log")
}

foreach ($name in $testVariables) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    if ([bool]$DockerHost -ne [bool]$SshTarget) {
        throw "DockerHost and SshTarget must be provided together."
    }
    if ($DockerHost) {
        docker context create $dockerContextName --docker "host=$DockerHost" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to create the temporary Docker context."
        }
        $dockerContextCreated = $true
        $env:DOCKER_CONTEXT = $dockerContextName
    }

    $accountAppPassword = New-TestPassword
    $accountMigrationPassword = New-TestPassword
    $transactionAppPassword = New-TestPassword
    $transactionMigrationPassword = New-TestPassword
    $startCommand = "/usr/sbin/sshd && sed -i 's/--min-cpu=/--max-cpu=$tenantMaxCpu --min-cpu=/' /root/boot/start.sh && /root/boot/start.sh"
    if ($CiProfile) {
        $startCommand += " || sleep 300"
    }
    $dockerId = docker run --detach --name $containerName `
        --memory $containerMemory --cpus $CpuLimit `
        --ulimit nofile=655350:655350 --ulimit stack=-1:-1 `
        --publish 127.0.0.1::2881 `
        --env MODE=MINI `
        --env EXIT_WHILE_ERROR=YES `
        --env "OB_MEMORY_LIMIT=$oceanBaseMemoryLimit" `
        --env OB_SYSTEM_MEMORY=1G `
        --env "OB_DATAFILE_SIZE=$oceanBaseDatafileSize" `
        --env "OB_LOG_DISK_SIZE=$oceanBaseLogDiskSize" `
        --env OB_TENANT_NAME=onecard `
        --env OB_TENANT_MIN_CPU=1 `
        --env "OB_TENANT_MAX_CPU=$tenantMaxCpu" `
        --env "OB_TENANT_MEMORY_SIZE=$tenantMemorySize" `
        --env "OB_TENANT_LOG_DISK_SIZE=$tenantLogDiskSize" `
        --entrypoint bash --workdir /root `
        $Image -lc $startCommand
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to start the isolated OceanBase smoke container."
    }
    $containerCreated = $true

    $portLine = docker port $containerName 2881/tcp
    if ($LASTEXITCODE -ne 0 -or $portLine -notmatch ":(?<port>\d+)$") {
        throw "Unable to resolve the loopback-only OceanBase port."
    }
    $hostPort = $Matches.port
    if ($SshTarget) {
        $remotePort = $hostPort
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $hostPort = $listener.LocalEndpoint.Port
        $listener.Stop()

        $sshTunnel = Start-Process -FilePath "ssh" -ArgumentList @(
            "-N",
            "-L", "127.0.0.1:${hostPort}:127.0.0.1:${remotePort}",
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
            $listenerState = Get-NetTCPConnection -State Listen -LocalPort $hostPort -ErrorAction SilentlyContinue
            if ($listenerState) {
                $tunnelReady = $true
                break
            }
            Start-Sleep -Milliseconds 100
        }
        if (-not $tunnelReady) {
            throw "The SSH tunnel did not become ready."
        }
    }

    $ready = $false
    for ($attempt = 1; $attempt -le 180; $attempt++) {
        $startupLogs = docker logs $containerName 2>&1 | Out-String
        if ($startupLogs -match "observer program health check x") {
            docker logs --tail 150 $containerName
            $traceIds = [regex]::Matches($startupLogs, "Trace ID: ([0-9a-f-]+)")
            if ($traceIds.Count -gt 0) {
                $traceId = $traceIds[$traceIds.Count - 1].Groups[1].Value
                docker exec $containerName obd display-trace $traceId
            }
            docker exec $containerName bash -lc "find /root -type f -name observer.log -exec grep -iE 'ERROR|FATAL|OB_SERVER|out of memory' {} + | tail -n 100"
            throw "OceanBase observer failed its startup health check."
        }
        $state = docker inspect --format '{{.State.Running}}' $containerName 2>$null
        if ($LASTEXITCODE -ne 0 -or $state -ne "true") {
            docker logs --tail 150 $containerName
            throw "OceanBase exited before the tenant became ready."
        }
        if ($startupLogs -match "create resource (unit|pool) .*execute failed") {
            docker logs --tail 150 $containerName
            $traceIds = [regex]::Matches($startupLogs, "Trace ID: ([0-9a-f-]+)")
            if ($traceIds.Count -gt 0) {
                $traceId = $traceIds[$traceIds.Count - 1].Groups[1].Value
                docker exec $containerName obd display-trace $traceId
            }
            docker exec $containerName bash -lc "find /root -type f -name observer.log -exec grep -iE 'resource pool|ERROR|OB_SERVER' {} + | tail -n 80"
            throw "OceanBase could not allocate its temporary tenant resource pool."
        }
        $tenantStatus = docker exec $containerName obclient -h 127.0.0.1 -u root@sys -P 2881 --connect-timeout=2 -N -e "SELECT STATUS FROM oceanbase.DBA_OB_TENANTS WHERE TENANT_NAME = 'onecard'" 2>$null | Select-Object -First 1
        if ($LASTEXITCODE -eq 0 -and $tenantStatus -eq "NORMAL") {
            $ready = $true
            break
        }
        if (($attempt % 12) -eq 0) {
            Write-Host "Waiting for OceanBase tenant readiness ($attempt/180)."
        }
        Start-Sleep -Seconds 5
    }
    if (-not $ready) {
        docker logs --tail 100 $containerName
        throw "OceanBase did not become ready within 15 minutes."
    }

    $sql = @"
CREATE DATABASE onecard_account;
CREATE DATABASE onecard_transaction;
CREATE USER 'onecard_account_app'@'%' IDENTIFIED BY '$accountAppPassword';
CREATE USER 'onecard_account_migrator'@'%' IDENTIFIED BY '$accountMigrationPassword';
CREATE USER 'onecard_transaction_app'@'%' IDENTIFIED BY '$transactionAppPassword';
CREATE USER 'onecard_transaction_migrator'@'%' IDENTIFIED BY '$transactionMigrationPassword';
GRANT SELECT, INSERT, UPDATE, DELETE ON onecard_account.* TO 'onecard_account_app'@'%';
GRANT ALL PRIVILEGES ON onecard_account.* TO 'onecard_account_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON onecard_transaction.* TO 'onecard_transaction_app'@'%';
GRANT ALL PRIVILEGES ON onecard_transaction.* TO 'onecard_transaction_migrator'@'%';
"@
    $sql | docker exec --interactive $containerName obclient -h 127.0.0.1 -u root@onecard -P 2881
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create the isolated schemas and least-privilege test users."
    }

    $credentialChecks = @(
        @{ User = "onecard_account_app@onecard"; Password = $accountAppPassword },
        @{ User = "onecard_account_migrator@onecard"; Password = $accountMigrationPassword },
        @{ User = "onecard_transaction_app@onecard"; Password = $transactionAppPassword },
        @{ User = "onecard_transaction_migrator@onecard"; Password = $transactionMigrationPassword }
    )
    foreach ($credential in $credentialChecks) {
        docker exec --env "MYSQL_PWD=$($credential.Password)" $containerName `
            obclient -h 127.0.0.1 -u $credential.User -P 2881 --connect-timeout=3 -N -e "SELECT 1" 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "A generated OceanBase smoke credential could not authenticate for user $($credential.User)."
        }
    }

    $env:ONECARD_DB_IT_ENABLED = "true"
    $env:ONECARD_DB_IT_URL = "jdbc:mysql://127.0.0.1:$hostPort/onecard_account"
    $env:ONECARD_DB_IT_SCHEMA = "onecard_account"
    $env:ONECARD_DB_IT_OTHER_SCHEMA = "onecard_transaction"
    $env:ONECARD_DB_IT_APP_USER = "onecard_account_app@onecard"
    $env:ONECARD_DB_IT_APP_PASSWORD = $accountAppPassword
    $env:ONECARD_DB_IT_MIGRATION_USER = "onecard_account_migrator@onecard"
    $env:ONECARD_DB_IT_MIGRATION_PASSWORD = $accountMigrationPassword
    $env:ONECARD_TRANSACTION_DB_IT_URL = "jdbc:mysql://127.0.0.1:$hostPort/onecard_transaction"
    $env:ONECARD_TRANSACTION_DB_IT_SCHEMA = "onecard_transaction"
    $env:ONECARD_TRANSACTION_DB_IT_OTHER_SCHEMA = "onecard_account"
    $env:ONECARD_TRANSACTION_DB_IT_APP_USER = "onecard_transaction_app@onecard"
    $env:ONECARD_TRANSACTION_DB_IT_APP_PASSWORD = $transactionAppPassword
    $env:ONECARD_TRANSACTION_DB_IT_MIGRATION_USER = "onecard_transaction_migrator@onecard"
    $env:ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD = $transactionMigrationPassword
    $env:ONECARD_DB_IT_ISOLATION_ENABLED = "false"
    foreach ($name in $testVariables) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, "Process"))) {
            throw "A required database integration environment variable is empty: $name"
        }
    }

    $migrationTestSuites = @(
        @{ Module = "services/onecard-account-service"; Test = "AccountDatabaseMigrationTest" },
        @{ Module = "services/onecard-transaction-service"; Test = "TransactionDatabaseMigrationTest" }
    )
    $suiteIndex = 0
    foreach ($suite in $migrationTestSuites) {
        $suiteIndex++
        Invoke-SecretScannedTest @(
            "--batch-mode", "--no-transfer-progress",
            "-pl", $suite.Module, "-am",
            "-Dtest=$($suite.Test)",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "test"
        ) "migration-$suiteIndex.log" "The OceanBase Spring JDBC/Flyway migration smoke failed for $($suite.Module)."
    }

    $env:ONECARD_DB_IT_ISOLATION_ENABLED = "true"
    Invoke-SecretScannedTest @(
        "--batch-mode", "--no-transfer-progress",
        "-pl", "services/onecard-account-service", "-am",
        "-Dtest=DatabaseSchemaIsolationTest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "test"
    ) "schema-isolation.log" "The OceanBase account/transaction schema isolation smoke failed."
    @(
        "# OceanBase account and transaction migration smoke",
        "",
        "- Temporary container: $containerName",
        "- Target: $(if ($SshTarget) { $SshTarget } else { 'local Docker engine' })",
        "- Resource profile: $(if ($CiProfile) { 'CI compact' } else { 'development' })",
        "- Generated credentials were checked against test output; no credential leak found.",
        "- Test logs are in this directory."
    ) | Set-Content -LiteralPath (Join-Path $outputDirectory "report.md")
    Write-Host "OceanBase migration smoke passed. Evidence: $outputDirectory"
}
catch {
    Save-DatabaseFailureDiagnostics
    throw
}
finally {
    foreach ($name in $testVariables) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
    if ($containerCreated) {
        docker rm --force $containerName | Out-Null
    }
    if ($sshTunnel -and -not $sshTunnel.HasExited) {
        Stop-Process -Id $sshTunnel.Id -Force
    }
    $env:DOCKER_CONTEXT = $savedDockerContext
    if ($dockerContextCreated) {
        docker context rm --force $dockerContextName | Out-Null
    }
}
