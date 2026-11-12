# ==========================================
# 完整版本地 CD：编译 + 测试 + K6 + 推 Docker
# ==========================================
$ErrorActionPreference = "Stop"

Write-Host "=== [1/5] Maven 编译与单元测试 ===" -ForegroundColor Cyan
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "=== [2/5] 启动服务并运行 K6 门禁 ===" -ForegroundColor Cyan
$jarPath = "services\onecard-account-service\target\onecard-account-service-1.0.0-SNAPSHOT.jar"
$process = Start-Process -FilePath "java" -ArgumentList "-Dspring.profiles.active=test -Dserver.port=8082 -jar $jarPath" -PassThru -WindowStyle Hidden

$isReady = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    try {
        $res = Invoke-WebRequest -Uri "http://localhost:8082/actuator/health" -UseBasicParsing -TimeoutSec 1
        $body = $res.Content | ConvertFrom-Json
        if ($res.StatusCode -eq 200 -and $body.status -eq "UP") { $isReady = $true; break }
    } catch {}
}

if (-not $isReady) {
    Write-Host "❌ 服务启动失败！" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "正在压测..." -ForegroundColor Yellow
k6 run --env BASE_URL=http://localhost:8082 tests/performance/smoke.js
$k6ExitCode = $LASTEXITCODE

Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue

if ($k6ExitCode -ne 0) {
    Write-Host "❌ K6 压测未通过，终止 CD 打包！" -ForegroundColor Red
    exit 1
}
Write-Host "✅ 压测通过！" -ForegroundColor Green

Write-Host "=== [3/5] 构建 Docker 镜像 ===" -ForegroundColor Cyan
$DOCKER_USER = "nytttt"
$IMAGE_NAME = "$DOCKER_USER/nyt"
$TAG = "latest"
$FULL_IMAGE = "${IMAGE_NAME}:${TAG}"

docker build -t $FULL_IMAGE ./services/onecard-account-service
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "=== [4/5] 推送镜像到 Docker Hub ===" -ForegroundColor Cyan
docker push $FULL_IMAGE
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "=== [5/5] 成功！ ===" -ForegroundColor Green
Write-Host "镜像已推送到 $FULL_IMAGE" -ForegroundColor Green
