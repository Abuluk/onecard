# ==========================================
# 本地一键构建推送脚本 (deploy.ps1)
# ==========================================

# 1. 配置你的 Docker Hub 账号
$DOCKER_USER = "nytttt" 
$IMAGE_NAME = "$DOCKER_USER/nyt" # 用户名/仓库名
$TAG = "latest"
$FULL_IMAGE = "${IMAGE_NAME}:${TAG}"

Write-Host "=== 开始本地构建 ===" -ForegroundColor Cyan

# 2. Maven 打包（全流程测试）
# 注意：确保你的 mvnw.cmd 指向 JDK 25
Write-Host "正在打包 Maven 模块..." -ForegroundColor Yellow
.\mvnw clean package
if ($LASTEXITCODE -ne 0) {
    Write-Host "Maven 构建失败，终止流程！" -ForegroundColor Red
    exit 1
}

# 3. Docker 构建镜像
Write-Host "正在构建 Docker 镜像..." -ForegroundColor Yellow
# 注意：这里 -t 后面直接使用 $FULL_IMAGE
docker build -t $FULL_IMAGE ./onecard-demo-service
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker 构建失败，终止流程！" -ForegroundColor Red
    exit 1
}

# 4. 推送镜像到 Docker Hub
Write-Host "正在推送到 Docker Hub..." -ForegroundColor Yellow
docker push $FULL_IMAGE
if ($LASTEXITCODE -ne 0) {
    Write-Host "推送失败，请检查是否已登录 Docker Hub！" -ForegroundColor Red
    exit 1
}

Write-Host "=== 构建推送成功！镜像: $FULL_IMAGE ===" -ForegroundColor Green