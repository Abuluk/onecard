# ==========================================
# 完整版本地 CI：编译 + 测试 + K6 压测
# ==========================================
$ErrorActionPreference = "Stop"

Write-Host "=== [1/4] Maven 编译与单元测试 ===" -ForegroundColor Cyan
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Maven 构建失败，终止流程！" -ForegroundColor Red
    exit 1
}

Write-Host "=== [2/4] 后台启动微服务 ===" -ForegroundColor Cyan
$jarPath = "services\onecard-account-service\target\onecard-account-service-1.0.0-SNAPSHOT.jar"
# 启动 Java 进程，并隐藏窗口
$process = Start-Process -FilePath "java" -ArgumentList "-Dspring.profiles.active=test -Dserver.port=8082 -jar $jarPath" -PassThru -WindowStyle Hidden

Write-Host "等待账户服务启动 (端口 8082)..." -ForegroundColor Yellow
$isReady = $false
# 循环 30 次（最多等待 30 秒）
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    try {
        # 尝试请求健康检查接口
        $response = Invoke-WebRequest -Uri "http://localhost:8082/actuator/health" -UseBasicParsing -TimeoutSec 1
        $body = $response.Content | ConvertFrom-Json
        if ($response.StatusCode -eq 200 -and $body.status -eq "UP") {
            $isReady = $true
            break
        }
    } catch {
        # 服务还没启动好，忽略错误继续等
    }
}

if (-not $isReady) {
    Write-Host "❌ 服务启动超时或失败！" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    exit 1
}
Write-Host "✅ 服务已就绪！" -ForegroundColor Green

Write-Host "=== [3/4] 运行 K6 性能门禁 ===" -ForegroundColor Cyan
k6 run --env BASE_URL=http://localhost:8082 tests/performance/smoke.js
$k6ExitCode = $LASTEXITCODE

Write-Host "=== [4/4] 清理环境 ===" -ForegroundColor Cyan
Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
Write-Host "后台服务已关闭" -ForegroundColor Gray

if ($k6ExitCode -ne 0) {
    Write-Host "❌ K6 压测失败（阈值未通过）！" -ForegroundColor Red
    exit 1
}

Write-Host "🎉 本地完整 CI 全部通过！" -ForegroundColor Green
