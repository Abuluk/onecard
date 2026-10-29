# 使用支持 Java 25 的轻量级 JRE 镜像
FROM eclipse-temurin:25-jre-alpine

# 设置工作目录
WORKDIR /app

# 复制 Maven 打包好的 jar 包
COPY target/*.jar app.jar

# 暴露 8080 端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]