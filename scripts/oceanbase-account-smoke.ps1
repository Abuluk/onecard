param(
    [string] $Image = "oceanbase/oceanbase-ce:4.4.2-lts@sha256:eaa2c3b3513fa21e4a658f197c1deb93c9d6b6eb742cfa5ef0f085f82b9cec54"
)

$ErrorActionPreference = "Stop"
$containerName = "onecard-oceanbase-smoke-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$containerCreated = $false
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

foreach ($name in $testVariables) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    $accountAppPassword = New-TestPassword
    $accountMigrationPassword = New-TestPassword
    $transactionAppPassword = New-TestPassword
    $transactionMigrationPassword = New-TestPassword
    $dockerId = docker run --detach --name $containerName `
        --memory 10g --cpus 4 `
        --ulimit nofile=65535:65535 --ulimit stack=-1:-1 `
        --publish 127.0.0.1::2881 `
        --env MODE=MINI `
        --env EXIT_WHILE_ERROR=NO `
        --env OB_MEMORY_LIMIT=6G `
        --env OB_SYSTEM_MEMORY=1G `
        --env OB_DATAFILE_SIZE=5G `
        --env OB_LOG_DISK_SIZE=5G `
        --env OB_TENANT_NAME=onecard `
        --env OB_TENANT_MIN_CPU=1 `
        --env OB_TENANT_MAX_CPU=2 `
        --env OB_TENANT_MEMORY_SIZE=1G `
        --env OB_TENANT_LOG_DISK_SIZE=2G `
        --entrypoint bash --workdir /root `
        $Image -lc "/usr/sbin/sshd && sed -i 's/--min-cpu=/--max-cpu=2 --min-cpu=/' /root/boot/start.sh && /root/boot/start.sh"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to start the isolated OceanBase smoke container."
    }
    $containerCreated = $true

    $portLine = docker port $containerName 2881/tcp
    if ($LASTEXITCODE -ne 0 -or $portLine -notmatch ":(?<port>\d+)$") {
        throw "Unable to resolve the loopback-only OceanBase port."
    }
    $hostPort = $Matches.port

    $ready = $false
    for ($attempt = 1; $attempt -le 180; $attempt++) {
        $state = docker inspect --format '{{.State.Running}}' $containerName 2>$null
        if ($LASTEXITCODE -ne 0 -or $state -ne "true") {
            docker logs --tail 150 $containerName
            throw "OceanBase exited before the tenant became ready."
        }
        $startupLogs = docker logs $containerName 2>&1 | Out-String
        if ($startupLogs -match "create resource (unit|pool) .*execute failed") {
            docker logs --tail 150 $containerName
            $traceIds = [regex]::Matches($startupLogs, "Trace ID: ([0-9a-f-]+)")
            if ($traceIds.Count -gt 0) {
                $traceId = $traceIds[$traceIds.Count - 1].Groups[1].Value
                docker exec $containerName obd display-trace $traceId
            }
            docker exec $containerName bash -lc "grep -iE 'resource pool|ERROR|OB_SERVER' /root/ob/log/observer.log | tail -n 80"
            throw "OceanBase could not allocate its temporary tenant resource pool."
        }
        docker exec $containerName obclient -h127.1 -uroot@onecard -P2881 --connect-timeout=2 -N -e "SELECT 1" 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
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
CREATE TABLE onecard_account.private_probe (id INT NOT NULL PRIMARY KEY);
CREATE TABLE onecard_transaction.private_probe (id INT NOT NULL PRIMARY KEY);
CREATE USER 'onecard_account_app'@'%' IDENTIFIED BY '$accountAppPassword';
CREATE USER 'onecard_account_migrator'@'%' IDENTIFIED BY '$accountMigrationPassword';
CREATE USER 'onecard_transaction_app'@'%' IDENTIFIED BY '$transactionAppPassword';
CREATE USER 'onecard_transaction_migrator'@'%' IDENTIFIED BY '$transactionMigrationPassword';
GRANT SELECT, INSERT, UPDATE, DELETE ON onecard_account.* TO 'onecard_account_app'@'%';
GRANT ALL PRIVILEGES ON onecard_account.* TO 'onecard_account_migrator'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON onecard_transaction.* TO 'onecard_transaction_app'@'%';
GRANT ALL PRIVILEGES ON onecard_transaction.* TO 'onecard_transaction_migrator'@'%';
"@
    $sql | docker exec --interactive $containerName obclient -h127.1 -uroot@onecard -P2881
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to create the isolated schemas and least-privilege test users."
    }

    $env:ONECARD_DB_IT_ENABLED = "true"
    $env:ONECARD_DB_IT_URL = "jdbc:mysql://127.0.0.1:$hostPort/onecard_account"
    $env:ONECARD_DB_IT_SCHEMA = "onecard_account"
    $env:ONECARD_DB_IT_OTHER_SCHEMA = "onecard_other"
    $env:ONECARD_DB_IT_APP_USER = "onecard_account_app"
    $env:ONECARD_DB_IT_APP_PASSWORD = $accountAppPassword
    $env:ONECARD_DB_IT_MIGRATION_USER = "onecard_account_migrator"
    $env:ONECARD_DB_IT_MIGRATION_PASSWORD = $accountMigrationPassword
    $env:ONECARD_TRANSACTION_DB_IT_URL = "jdbc:mysql://127.0.0.1:$hostPort/onecard_transaction"
    $env:ONECARD_TRANSACTION_DB_IT_SCHEMA = "onecard_transaction"
    $env:ONECARD_TRANSACTION_DB_IT_OTHER_SCHEMA = "onecard_account"
    $env:ONECARD_TRANSACTION_DB_IT_APP_USER = "onecard_transaction_app"
    $env:ONECARD_TRANSACTION_DB_IT_APP_PASSWORD = $transactionAppPassword
    $env:ONECARD_TRANSACTION_DB_IT_MIGRATION_USER = "onecard_transaction_migrator"
    $env:ONECARD_TRANSACTION_DB_IT_MIGRATION_PASSWORD = $transactionMigrationPassword

    mvn --batch-mode --no-transfer-progress `
        -pl services/onecard-account-service -am `
        '-Dtest=AccountDatabaseMigrationTest,TransactionDatabaseMigrationTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' `
        test
    if ($LASTEXITCODE -ne 0) {
        throw "The OceanBase Spring JDBC/Flyway migration smoke failed."
    }
}
finally {
    foreach ($name in $testVariables) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
    if ($containerCreated) {
        docker rm --force $containerName | Out-Null
    }
}
