# 微信云托管代码仓库流水线构建入口：后端源码位于 backend/，管理端 SPA 一并打进同一容器（docs/11 §5 单容器方案）
#
# 运行时使用「分层 jar」布局（docs/11 §0）：依赖层（dependencies）与业务层（application）分开 COPY，
# 云托管默认探针只有 2s 初始延迟 + 约 150s 窗口，而整包镜像拉取约 180s 会被判失败；
# 分层后后续发布只变更 application 层（几 MB），拉取可落进窗口。改动 pom 的 layers 配置需与此保持一致。

FROM node:20-alpine AS admin-build
WORKDIR /admin
COPY admin/package.json admin/package-lock.json /admin/
RUN npm ci --no-fund --no-audit
COPY admin/ /admin/
# permission-v2.js 经 ESM 桥引用 admin 目录外的单一权限镜像（../../../constants/…），
# 云端构建上下文必须带入，否则 vite build 报 Could not resolve（2026-11-15 #194 构建失败根因）
COPY constants/permission-matrix.js /constants/permission-matrix.js
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY backend/src /app/src
# 清掉本地残留旧管理端 dist，避免 COPY 合并后旧哈希资源进 jar（#195 旧版管理端根因）
RUN rm -rf /app/src/main/resources/static
COPY --from=admin-build /admin/dist /app/src/main/resources/static
COPY backend/settings.xml backend/pom.xml /app/
RUN mvn -s /app/settings.xml -f /app/pom.xml clean package -Dmaven.test.skip=true

# 解包分层：dependencies / spring-boot-loader / snapshot-dependencies / application
FROM eclipse-temurin:17-jre-alpine AS extract
WORKDIR /extract
COPY --from=build /app/target/*.jar /extract/app.jar
RUN java -Djarmode=layertools -jar /extract/app.jar extract

# 运行时与 backend/Dockerfile 对齐 jammy（glibc）：alpine musl 的 DNS 并行查询在 VPC DNS 下
# 丢包致 api.weixin.qq.com 解析失败（C-14），不得回退 alpine
FROM eclipse-temurin:17-jre-jammy
ENV SERVER_PORT=80
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo Asia/Shanghai > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=extract /extract/dependencies/ ./
COPY --from=extract /extract/spring-boot-loader/ ./
COPY --from=extract /extract/snapshot-dependencies/ ./
COPY --from=extract /extract/application/ ./
EXPOSE 80
# 分层布局不能用 -jar（无入口清单），启动器改为 JarLauncher（Spring Boot 3.2+ 包名）
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
