# 构建阶段：JDK 17 + Maven（腾讯云镜像加速）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY src /app/src
COPY settings.xml pom.xml /app/
RUN mvn -s /app/settings.xml -f /app/pom.xml clean package -DskipTests

# 运行阶段：JRE 17（alpine 体积小）
FROM eclipse-temurin:17-jre-alpine
ENV SERVER_PORT=80
RUN apk add --no-cache ca-certificates tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo Asia/Shanghai > /etc/timezone
WORKDIR /app
COPY --from=build /app/target/*.jar /app/app.jar
# 端口必须与「服务设置」中填写的监听端口一致（云托管模板约定 80）
EXPOSE 80
# CMD 只保留最后一行有效命令，多行 CMD 只有最后一行会执行
CMD ["java", "-jar", "/app/app.jar"]
