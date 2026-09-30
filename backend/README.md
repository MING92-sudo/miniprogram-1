# maintenance-backend（P3 平台写链路后端：token 中控 + 41 条业务契约 + 2.6 上报）

Spring Boot 3.3.4（Java 17 / Maven / MyBatis-Plus / MySQL 8 / Flyway）。
P2/P3 落地范围（docs/08）：小程序全部 41 条业务路由真实后端化、平台 token 中控、2.2/2.7 只读转发、
每日 09:00 自动派单、COS 代理上传、LBS 逆地址解析代理（key 不落前端）；
P3（V1.8）：签退后自动转发 2.6（失败不自动重试）+ `reg_upload_log` 脱敏落库 + 手动重报、
2.3/2.4 登记端点、2.5 人员同步、2.8 存量推送（开关默认关）。
**不含**：Vue3 管理端 / STS 直传 / Redis（后置）。

## 当前状态（2026-10-01 与代码核对）

| 组件 | 状态 |
|---|---|
| 认证（/auth/*，JWT + BCrypt + 云托管免鉴权 openid） | ✅ |
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
| Redis / STS / Vue3 管理端 | 🔴 后置 |

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
DB_USER=xxx
DB_PASSWORD=xxx
```

验证：`GET http://localhost:8080/health`；登录演示账号 `13800000001 / 123456`。

## 环境变量（全部经环境变量注入，严禁写真实值入库，AGENTS §2）

| 变量 | 说明 |
|---|---|
| `REG_*`（auth-login-url/api-base-url/username/key/appcode/secret） | 平台凭证，P2 转发必需 |
| `REG_RETRY_AUTO` | **必须保持 false**（幂等性未获平台书面确认，AGENTS §2.3） |
| `REG_LEGACY_UPLOAD_ENABLED` | 2.8 存量推送开关，默认 false（平台关闭存量接口后置 false） |
| `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` | MySQL 连接 |
| `JWT_SECRET` | 自建 JWT 签名密钥（生产必须强随机） |
| `COS_REGION/COS_BUCKET` | 对象存储桶名/地域（application.yml 已带云托管托管桶默认值，不配则本地磁盘回退） |
| `COS_AUTH_URL` | 云托管内网临时凭证接口（托管桶自动使用，默认 `/_/cos/getauth`，无需密钥） |
| `COS_SECRET_ID/COS_SECRET_KEY` | 仅自建 COS 桶（本地联调）需要；云托管托管桶无静态密钥 |
| `LBS_AMAP_KEY / LBS_TENCENT_KEY` | 逆地址解析（前端不接触 key） |
| `SEED_DEMO_DATA` | 演示种子数据开关，生产置 false |

## 测试

```bash
mvn test   # token 中控 / 401 重试 / 表单编码 / 派单 6 台同日一次性 09:00 / 检查项模板 / 2.6 上报与重报 / 2.8 开关 / PDF 导出
```

## 微信云托管部署

1. 流水线构建目录 `backend/`，监听端口 80（`SERVER_PORT=80`）；
2. 开启「小程序免鉴权调用」，请求头自动携带 `X-WX-OPENID`；
3. 上表环境变量在「服务设置 → 环境变量」配置；
4. MySQL 使用云托管数据库（版本 8.0，utf8mb4）。
