# maintenance-backend（P3 平台写链路后端：token 中控 + 41 条业务契约 + 2.6 上报）

Spring Boot 3.3.4（Java 17 / Maven / MyBatis-Plus / MySQL 8 / Flyway）。
P2/P3 落地范围（docs/08）：小程序全部 41 条业务路由真实后端化、平台 token 中控、2.2/2.7 只读转发、
每日 09:00 自动派单、COS 代理上传、LBS 逆地址解析代理（key 不落前端）；
P3（V1.8）：签退后自动转发 2.6（失败不自动重试）+ `reg_upload_log` 脱敏落库 + 手动重报、
2.3/2.4 登记端点、2.5 人员同步、2.8 存量推送（开关默认关）。
**不含**：Vue3 管理端 / STS 直传 / Redis（后置）。

## 当前状态（2026-10-02 与代码核对）

| 组件 | 状态 |
|---|---|
| 认证（/auth/*，JWT + BCrypt + code→jscode2session 真实 openid） | ✅ |
| 微信绑定防串号（openid 唯一约束 + 存量串号清理 V6 迁移） | ✅ |
| 平台 token 中控（GET 登录 / TTL=expires_in−60s / 401 重登重试 1 次） | ✅ |
| 2.2/2.7 只读转发（POST 表单，code 兼容数字/字符串） | ✅（`POST /platform/sync` 触发落库） |
| 2.6 上报（签退自动转发 / FAILED 手动重报 / `reg_upload_log` 脱敏日志） | ✅ |
| 2.3/2.4 登记转发（multipart）/ 2.5 人员 platform_id 同步 / 2.8 开关化推送 | ✅ |
| 维保记录 PDF 导出（`GET /admin/records/{id}/export-pdf`，含照片/签字） | ✅ |
| 41 条业务路由（路径与 mock 契约一致，`{ code, message, data }` 信封） | ✅ |
| 每日 09:00 派单 + 业务触达即时补派（口径同 `scripts/verify-dispatch.js`） | ✅ |
| 文件上传（COS 代理，未配 COS 时回退本地磁盘） | ✅ |
| LBS `/location/reverse` 代理（高德/腾讯，key 走环境变量） | ✅ |
| 幂等（X-Idempotency-Key 全部写接口去重，重放返回首次响应） | ✅ |
| 管理端路由（docs/09 一期，V2.0：dashboard/records/stats/elevators 富视图/upload-logs/sync-status + 四类档案 CRUD + 角色门禁 + 1002 互斥预校验 + client=admin 登录白名单） | ✅ |
| Redis / STS | 🔴 后置 |

## 本地运行

```powershell
cd backend
$env:JAVA_HOME='...jdk-17'        # 若未全局安装
mvn spring-boot:run               # 默认 8080；首次启动 Flyway 建表 + 写入演示种子数据
```

**.env 加载（V1.5 起）**：Spring Boot 启动时自动导入根目录 `.env`（与平台联调脚本共用）。
在 `.env` 追加云端数据库连接即可，无需 `$env:` 注入：

```ini
DB_HOST=你的云端MySQL地址
DB_PORT=3306
DB_NAME=maintenance
DB_USERNAME=xxx
DB_PASSWORD=xxx
```

验证：`GET http://localhost:8080/health`；登录演示账号 `13800000001 / 123456`。

## 环境变量（全部经环境变量注入，严禁写真实值入库，AGENTS §2）

| 变量 | 说明 |
|---|---|
| `REG_*`（auth-login-url/api-base-url/username/key/appcode/secret） | 平台凭证，P2 转发必需 |
| `REG_LEGACY_UPLOAD_ENABLED` | 2.8 存量推送开关，默认 false（平台关闭存量接口后置 false） |
| `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD` | MySQL 连接（`DB_USER` 为兼容旧名的回落，新部署一律用 `DB_USERNAME`） |
| `JWT_SECRET` | 自建 JWT 签名密钥（生产必须强随机） |
| `WX_APPID` / `WX_APPSECRET` | 微信小程序凭证（**生产必填**）：`/auth/bind-wechat`、`/auth/wx-login` 用 `wx.login` 的 code 调 `jscode2session` 换真实 openid。openid 无其他来源（`DEV_OPENID` / `X-WX-OPENID` 兜底已按 P0 修复移除） |
| `COS_REGION/COS_BUCKET` | 对象存储桶名/地域（application.yml 已带云托管托管桶默认值，不配则本地磁盘回退） |
| `COS_AUTH_URL` | 云托管内网临时凭证接口（托管桶自动使用，默认 `/_/cos/getauth`，无需密钥） |
| `COS_SECRET_ID/COS_SECRET_KEY` | 仅自建 COS 桶（本地联调）需要；云托管托管桶无静态密钥 |
| `LBS_AMAP_KEY / LBS_TENCENT_KEY` | 逆地址解析（前端不接触 key） |
| `SEED_DEMO_DATA` | 演示种子数据开关，生产置 false |

## 测试

```bash
mvn test   # token 中控 / 401 重试 / 表单编码 / 派单 6 台同日一次性 09:00 / 检查项模板 / 2.6 上报与重报 / 2.8 开关 / PDF 导出 / 微信 code→openid 与绑定防串号
```

`ApplicationBootDbTest`（Spring 上下文 + Flyway V1—V7 + `@Transactional` 代理）由环境变量 `P0_TEST_MYSQL_URL/USER/PASSWORD` 门控，**缺任一项整类静默跳过**（表现为"测试全绿"但上下文从未加载过）。本地验证迁移时须显式提供，CI 已在 `.github/workflows/backend-build.yml` 配好这三个变量。

## 微信云托管部署

1. 流水线构建目录 `backend/`，监听端口 80（`SERVER_PORT=80`）；
2. 「小程序免鉴权调用」可开可不开：openid 主路径是 `WX_APPID`/`WX_APPSECRET` 的 jscode2session，方案 A 直连域名同样可用（`X-WX-OPENID` 已不再被后端信任/读取）；
3. 上表环境变量在「服务设置 → 环境变量」配置；
4. MySQL 使用云托管数据库（版本 8.0，utf8mb4）。
