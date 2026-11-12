param(
    [string] $DockerHost,
    [string] $SshTarget,
    [string] $Image = "valkey/valkey:9.1.3@sha256:3add1a336a920ea54770a001ab6feb3da5fceea96a40373a3a2cd469b815f0b7"
)

$ErrorActionPreference = "Stop"
$containerName = "onecard-valkey-rate-smoke-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$dockerContextName = "onecard-valkey-rate-smoke-context-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$containerCreated = $false
$dockerContextCreated = $false
$sshTunnel = $null
$savedDockerContext = $env:DOCKER_CONTEXT
$savedEnabled = [Environment]::GetEnvironmentVariable("ONECARD_VALKEY_IT_ENABLED", "Process")
$savedUrl = [Environment]::GetEnvironmentVariable("ONECARD_VALKEY_IT_URL", "Process")

try {
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

    docker run --detach --name $containerName --memory 128m `
        --publish 127.0.0.1::6379 `
        $Image valkey-server --save "" --appendonly no --maxmemory 96mb --maxmemory-policy noeviction | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to start the isolated Valkey rate-limit container."
    }
    $containerCreated = $true

    $portLine = docker port $containerName 6379/tcp
    if ($LASTEXITCODE -ne 0 -or $portLine -notmatch ":(?<port>\d+)$") {
        throw "Unable to resolve the loopback-only Valkey port."
    }
    $remotePort = $Matches.port
    $localPort = $remotePort
    if ($SshTarget) {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $localPort = $listener.LocalEndpoint.Port
        $listener.Stop()

        $sshTunnel = Start-Process -FilePath "ssh" -ArgumentList @(
            "-N",
            "-L", "127.0.0.1:${localPort}:127.0.0.1:${remotePort}",
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
            if (Get-NetTCPConnection -State Listen -LocalPort $localPort -ErrorAction SilentlyContinue) {
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
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        $response = docker exec $containerName valkey-cli ping 2>$null
        if ($LASTEXITCODE -eq 0 -and $response -eq "PONG") {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {
        throw "Valkey did not become ready."
    }

    $env:ONECARD_VALKEY_IT_ENABLED = "true"
    $env:ONECARD_VALKEY_IT_URL = "redis://127.0.0.1:$localPort"
    .\mvnw.cmd --batch-mode --no-transfer-progress `
        -pl gateway `
        '-Dtest=GatewayValkeyRateLimitIntegrationTest' `
        test
    if ($LASTEXITCODE -ne 0) {
        throw "Gateway real-Valkey rate-limit integration tests failed."
    }
}
finally {
    [Environment]::SetEnvironmentVariable("ONECARD_VALKEY_IT_ENABLED", $savedEnabled, "Process")
    [Environment]::SetEnvironmentVariable("ONECARD_VALKEY_IT_URL", $savedUrl, "Process")
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
