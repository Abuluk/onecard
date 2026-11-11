$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$outputDirectory = Join-Path $root "artifacts/database-fail-closed-$([guid]::NewGuid().ToString('N').Substring(0, 12))"
$services = @(
    @{ Name = "account"; Module = "services/onecard-account-service"; Jar = "services/onecard-account-service/target/onecard-account-service-1.0.0-SNAPSHOT.jar" },
    @{ Name = "transaction"; Module = "services/onecard-transaction-service"; Jar = "services/onecard-transaction-service/target/onecard-transaction-service-1.0.0-SNAPSHOT.jar" }
)

function Get-FreeLocalPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    return $port
}

try {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    & (Join-Path $root "mvnw.cmd") --batch-mode --no-transfer-progress `
        -pl services/onecard-account-service,services/onecard-transaction-service -am '-DskipTests' package
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to build the database fail-closed smoke targets."
    }

    $results = [System.Collections.Generic.List[string]]::new()
    foreach ($service in $services) {
        $port = Get-FreeLocalPort
        $jdbcUrl = "jdbc:mysql://127.0.0.1:$port/onecard_smoke"
        $stdout = Join-Path $outputDirectory "$($service.Name).stdout.log"
        $stderr = Join-Path $outputDirectory "$($service.Name).stderr.log"
        $jarPath = Join-Path $root $service.Jar
        $process = Start-Process -FilePath "java" -ArgumentList @(
            "-jar", "`"$jarPath`"",
            "--spring.profiles.active=dev",
            "--spring.cloud.nacos.config.enabled=false",
            "--spring.cloud.nacos.discovery.enabled=false",
            "--spring.datasource.url=$jdbcUrl",
            "--spring.datasource.username=phase1-smoke",
            "--spring.datasource.password=phase1-smoke",
            "--spring.flyway.enabled=true",
            "--spring.flyway.url=$jdbcUrl",
            "--spring.flyway.user=phase1-smoke",
            "--spring.flyway.password=phase1-smoke",
            "--server.port=0", "--management.server.port=0"
        ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        if (-not $process.WaitForExit(60000)) {
            Stop-Process -Id $process.Id -Force
            throw "$($service.Name) did not fail startup within 60 seconds when its database was unavailable."
        }
        $process.Refresh()
        $logs = (Get-Content -LiteralPath $stdout -Raw) + (Get-Content -LiteralPath $stderr -Raw)
        if ($process.ExitCode -eq 0) {
            throw "$($service.Name) started successfully without its enabled Flyway database."
        }
        if ($logs -notmatch "(?i)(flyway|connection refused|connect exception|hikari)") {
            throw "$($service.Name) failed without identifying the unavailable database. See $stdout and $stderr."
        }
        $results.Add("$($service.Name): exited $($process.ExitCode) with database migration/connectivity failure")
    }

    @(
        "# Database unavailable fail-closed smoke",
        "",
        "- Target: account and transaction services with Flyway explicitly enabled.",
        "- Database endpoint: per-service ephemeral loopback port with no listener.",
        "- Result: $($results -join '; ').",
        "- Startup logs are in this directory."
    ) | Set-Content -LiteralPath (Join-Path $outputDirectory "report.md")
    Write-Host "Database fail-closed smoke passed. Evidence: $outputDirectory"
} catch {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
    throw
}
