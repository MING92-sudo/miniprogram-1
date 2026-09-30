# maintenance-backend（自建后端：平台 token 中控 + 接口转发层）

Spring Boot 3.3.4（Java 17 / Maven）后端骨架，定位为小程序与监管平台（重庆市智慧特种设备安全管理系统）之间的 **token 中控 + 接口转发层**：

- 平台凭证（`REG_*`）只存在于此服务进程，小程序前端永远不接触真实凭证；
- 小程序统一调用 `https://<本服务域名>/api/v1/**`；
- 本服务用 `PlatformTokenService` 登录换 token 并缓存，`PlatformClient` 转发业务请求。

## 当前状态（2026-09-30 与代码核对）

| 组件 | 状态 |
|---|---|
| `HealthController`（`GET /health`） | ✅ 可用 |
| `CountController`（`/api/count`，云托管联调示例） | ✅ 可用（内存计数） |
| `AuthController`（`POST /api/v1/auth/login`） | 🔴 返回 501，code2session → JWT 未实现 |
| `ReportController`（`POST /api/v1/reports`） | 🔴 返回 501，校验/落库/转发未实现 |
| `PlatformTokenService` | 🔴 缓存骨架已写，`getToken()` 抛 `UnsupportedOperationException` |
| `PlatformClient` | 🔴 `forward()` 抛 `UnsupportedOperationException` |
| 数据库 / COS / 鉴权拦截器 | 🔴 未接入 |

> 前端小程序当前（`config/index.js` `useMock: true`）**尚未调用本后端**；接通方式见根目录 README「小程序前端」一节。

## 目录

```
backend/
├─ pom.xml
└─ src/main/
   ├─ java/com/cqwlw/maintenance/
   │  ├─ MaintenanceBackendApplication.java   # 启动类
   │  ├─ config/        # PlatformProperties（REG_* 环境变量注入）、RestTemplate
   │  ├─ common/        # ApiResponse 统一响应、GlobalExceptionHandler
   │  ├─ controller/    # health / count / auth / reports 入口
   │  └─ service/       # 平台 token 中控、平台转发（均为 TODO 骨架）
   └─ resources/application.yml、application.example.env
```

## 运行

```bash
cd backend
mvn spring-boot:run   # 本地开发默认 8080（PowerShell: $env:SERVER_PORT='8080' 可自定义）
```

启动后验证：`GET http://localhost:8080/health`

> 注意：Java 端**不读取** `.env`（无 dotenv 依赖）。本地运行需手动 `$env:` 注入 `REG_*` 环境变量。

## 微信云托管部署

已按 wxcloudrun-springboot 模板规范对齐：容器监听端口 **80**、`Dockerfile`（JDK 17 多阶段构建）、`settings.xml`（腾讯云 Maven 镜像）、`container.config.json`。仓库根目录 Dockerfile 也会转发到本目录构建。

### 部署/发布流程（二开仓库 MING92-sudo/miniprogram-1）

```bash
git add backend
git commit -m 'update' && git push -u origin master
```

随后在云托管控制台：

1. 流水线/手动上传时构建目录选择 `backend/`（或根目录 Dockerfile）；
2. 「服务设置」监听端口保持 **80**；
3. `REG_*` 平台凭证在「服务设置 → 环境变量」配置，严禁写入代码或 `container.config.json`；
4. 幂等性未经平台书面确认前，`REG_RETRY_AUTO` 必须保持 `false`。

## 待实现（按优先级）

1. **平台 token 中控**（`PlatformTokenService`）：⚠ 实测 2.1 登录为 **GET + 查询串**（规范 2.1 写 POST + Body 是错的）；token 为 JWT，`expires_in≈3599`，缓存 TTL = expires_in − 60s；401 时清缓存重登重试 1 次（见 `docs/07`）。
2. **2.2/2.7 只读转发**（`PlatformClient`）：POST + `x-www-form-urlencoded` 表单（非 JSON）；code 兼容数字/字符串 `200`。
3. **微信登录**：code2session → openid → 自建 JWT（`AuthController`）。
4. **2.6 上报闭环**（`ReportController`）：签退冻结快照 → 表单组装（20 字段已实测确认，`workMan2Id` 必填）→ `reg_upload_log` 脱敏落库 → 保守重试（默认不自动重试）。
5. 2.3/2.4（multipart + contractFile/certificateFile）与 2.5 人员 `platform_id` 同步（按证书号匹配）。
6. 数据库（最小 schema：电梯/工单/记录/上报日志）、鉴权拦截器、文件上传代理。
