# maintenance-backend

Spring Boot 3.3.4（Java 17 / Maven / MyBatis-Plus / MySQL 8 / Flyway V1—V61，59 个迁移文件含 V30/V32 缺号）。
系统事实、业务不变量（§3）、平台对接实测结论（§4）与上线门禁（§6）以根目录 `docs/00-项目摘要.md` 为唯一事实源；
本文只保留运行/配置指引。注释中残留的旧文档编号引用（docs/01—16）所指文档已删除，可从 git 历史查阅。

## 本地运行

```bash
# 从仓库根启动（workingDirectory 相对 backend 解析，必须传绝对路径，否则读不到根 .env）
mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.workingDirectory=$(pwd -W)"
```

默认端口 8080；首次启动 Flyway 建表，`SEED_DEMO_DATA=true` 时写入演示种子数据（生产必须 false）。
验证：`GET http://localhost:8080/health`。

**.env**：根目录 `.env`（gitignore）配置 `DB_*` 与 `JWT_SECRET`（≥32 字节，无默认值，缺失即启动失败）；
模板见根 `.env.example`。

## 环境变量（全部经环境变量注入，严禁真实值入库/入文档，AGENTS §3.1）

| 变量 | 说明 |
|---|---|
| `REG_*`（平台四凭证 + 两个 URL） | 监管平台对接必需；`REG_LEGACY_UPLOAD_ENABLED`（2.8 存量推送）默认 false |
| `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` | MySQL 连接 |
| `JWT_SECRET` / `JWT_EXPIRE_HOURS` | 自建 JWT 签名密钥（生产必须强随机）/ 会话时长 |
| `COS_BUCKET/COS_REGION/COS_AUTH_URL` | 对象存储（托管桶走内网临时凭证）；`COS_SECRET_ID/KEY` 仅自建桶 |
| `SEED_DEMO_DATA` | 演示种子数据开关，生产置 false |
| `APP_WORK_DURATION_MINUTES` | 作业时长下限（默认 30，上线口径待确认，docs/00 §6 A-07） |
| `APP_CHECKIN_THRESHOLD_METERS` | 签到围栏全局默认（默认 200，电梯级优先） |
| `APP_LEGACY_MODULES_ENABLED` | 已裁模块总开关，默认 false（一律 403） |
| `SERVER_PORT` | 容器端口 80 |

**自动重试未实现且禁止实现**：`originalRecordId` 幂等性未获平台书面确认前，失败仅标记状态、
逐条人工重报（AGENTS §3.3，docs/00 §6 A-01）。

## 测试

```bash
mvn test   # 800+ 单测：token 中控 / 401 重试 / 派单 / 模板 / 签到围栏 / 动态码 / 生命周期 / PDF 等
```

派单与模板链路回归另跑（仓库根）：`node scripts/verify-dispatch.js`、`node scripts/verify-templates.js`。
CI（`.github/workflows/backend-build.yml`）四道门禁：后端 `mvn test`、回归脚本、根 lint、admin lint/test/build。

## 微信云托管部署

1. 根 `Dockerfile` 多阶段构建（Node 构建 admin → Maven 打包 → JRE 17 Alpine），监听端口 80（`SERVER_PORT=80`）；
2. 健康检查 `GET /health`；小程序经 `wx.cloud.callContainer` 访问；
3. 上表环境变量在「服务设置 → 环境变量」配置；
4. MySQL 使用云托管数据库（版本 8.0，utf8mb4/utf8mb4_general_ci）。
