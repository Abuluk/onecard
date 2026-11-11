$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$outputDirectory = Join-Path $root "artifacts/prod-config-fail-closed-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$environmentNames = @(
    "SPRING_PROFILES_ACTIVE",
    "SPRING_CLOUD_NACOS_CONFIG_SERVER_ADDR",
    "SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR",
    "SERVER_PORT",
    "MANAGEMENT_SERVER_PORT"
)
$savedEnvironment = @{}

function Get-FreeLocalPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    & (Join-Path $root "mvnw.cmd") --batch-mode --no-transfer-progress `
        -pl gateway,services/onecard-account-service,services/onecard-transaction-service,services/onecard-access-service,services/onecard-clearing-service,services/onecard-device-service,services/onecard-notification-service,services/onecard-payment-adapter-service,services/onecard-risk-service -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to build the production-profile smoke targets."
    }

    $services = @(
        @{ Name = "gateway"; Jar = "gateway/target/onecard-gateway-1.0.0-SNAPSHOT.jar" },
        @{ Name = "account"; Jar = "services/onecard-account-service/target/onecard-account-service-1.0.0-SNAPSHOT.jar" },
        @{ Name = "transaction"; Jar = "services/onecard-transaction-service/target/onecard-transaction-service-1.0.0-SNAPSHOT.jar" },
        @{ Name = "access"; Jar = "services/onecard-access-service/target/onecard-access-service-1.0.0-SNAPSHOT.jar" },
        @{ Name = "clearing"; Jar = "services/onecard-clearing-service/target/onecard-clearing-service-1.0.0-SNAPSHOT.jar" },
        @{ Name = "device"; Jar = "services/onecard-device-service/target/onecard-device-service-1.0.0-SNAPSHOT.jar" },
        @{ Name = "notification"; Jar = "services/onecard-notification-service/target/onecard-notification-service-1.0.0-SNAPSHOT.jar" },
        @{ Name = "payment-adapter"; Jar = "services/onecard-payment-adapter-service/target/onecard-payment-adapter-service-1.0.0-SNAPSHOT.jar" },
        @{ Name = "risk"; Jar = "services/onecard-risk-service/target/onecard-risk-service-1.0.0-SNAPSHOT.jar" }
    )
    $results = [System.Collections.Generic.List[string]]::new()
    foreach ($service in $services) {
        $port = Get-FreeLocalPort
        $env:SPRING_PROFILES_ACTIVE = "prod"
        $env:SPRING_CLOUD_NACOS_CONFIG_SERVER_ADDR = "127.0.0.1:$port"
        $env:SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR = "127.0.0.1:$port"
        $env:SERVER_PORT = "0"
        $env:MANAGEMENT_SERVER_PORT = "0"
        $stdout = Join-Path $outputDirectory "$($service.Name).stdout.log"
        $stderr = Join-Path $outputDirectory "$($service.Name).stderr.log"
        $jarPath = Join-Path $root $service.Jar
        $process = Start-Process -FilePath "java" -ArgumentList @(
            "-jar", "`"$jarPath`"",
            "--server.port=0",
            "--management.server.port=0"
        ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        if (-not $process.WaitForExit(60000)) {
            Stop-Process -Id $process.Id -Force
            throw "$($service.Name) production startup did not fail within 60 seconds."
        }
        $process.Refresh()
        $logs = (Get-Content -LiteralPath $stdout -Raw) + (Get-Content -LiteralPath $stderr -Raw)
        if ($process.ExitCode -eq 0) {
            throw "$($service.Name) started successfully without the required Nacos configuration."
        }
        if ($logs -notmatch "(?i)nacos") {
            throw "$($service.Name) failed without identifying the required Nacos configuration. See $stdout and $stderr."
        }
        $results.Add("$($service.Name): exited $($process.ExitCode) with Nacos config failure")
    }

    @(
        "# Production required Nacos config smoke",
        "",
        "- Target: all eight service and Gateway production profiles.",
        "- Nacos endpoint: a local ephemeral port with no listener.",
        "- Result: $($results -join '; ').",
        "- Logs are in this directory."
    ) | Set-Content -LiteralPath (Join-Path $outputDirectory "report.md")
    Write-Host "Production Nacos fail-closed smoke passed. Evidence: $outputDirectory"
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
}
