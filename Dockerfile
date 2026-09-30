# 微信云托管代码仓库流水线构建入口：实际后端源码位于 backend/
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY backend/src /app/src
COPY backend/settings.xml backend/pom.xml /app/
RUN mvn -s /app/settings.xml -f /app/pom.xml clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
ENV SERVER_PORT=80
RUN apk add --no-cache ca-certificates tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo Asia/Shanghai > /etc/timezone
WORKDIR /app
COPY --from=build /app/target/*.jar /app/app.jar
EXPOSE 80
CMD ["java", "-jar", "/app/app.jar"]
