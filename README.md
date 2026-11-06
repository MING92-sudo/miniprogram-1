# 智慧特种设备维保小程序（电梯无纸化维保）

> 最后更新：2026-11-05（与代码机审同步：`app.json` 20 页、mock 46 条路由、后端 100 条业务路由 / 184 单测、Flyway V1—V21）

## 项目组成

| 部分 | 位置 | 现状 |
|---|---|---|
| 微信小程序前端 | 仓库根目录（微信开发者工具打开） | ✅ 20 页，`useMock: false` 默认接本地后端；mock 46 条路由可独立演示 |
| 自建后端 | `backend/`（Spring Boot 3.3.4 / Java 17 / Maven） | ✅ 100 条业务路由（+3 探针）、Flyway V1—V21、184 单测、平台 2.1—2.8 转发、PDF 导出、COS 代理 |
| Web 管理端 | `admin/`（Vue3 + Vite + Element Plus，docs/09） | ✅ 13 条业务路由（+登录/403/404）：看板/工单/急修单/上报异常/计划调度/模板/台账/档案/平台登记 |
| 平台联调脚本 | `scripts/` | ✅ `test-full.ps1` 等已实测打通 2.1—2.8；`verify-dispatch.js`、`verify-templates.js` 为回归脚本 |
| 项目文档 | `docs/` | 需求、架构、UI、接口、测试、平台确认、联调、计划、管理端、部署、**未完成清单**（01—12） |

**当前状态与剩余工作**：见 [docs/12-未完成事项清单.md](docs/12-未完成事项清单.md)（唯一待办清单，含上线门禁）与 [docs/08-项目审查与开发计划.md](docs/08-项目审查与开发计划.md)（阶段与历史）。

## 快速上手（本地三件套）

```bash
# 1) 后端（需 JDK 17 + 本地 MySQL；库表由 Flyway 自动迁移）
cd backend && mvn spring-boot:run          # http://localhost:8080/health

# 2) 管理端
cd admin && npm install && npm run dev     # http://localhost:5173，/api 代理到 8080

# 3) 小程序：微信开发者工具打开仓库根目录，确保 config/index.js 为 ENV='dev'、useMock:false
```

本地 `.env` 只放平台凭证与数据库口令（已 gitignore）；缺 `JWT_SECRET` 后端启动即失败（无默认密钥）。

## 小程序前端

- **技术栈**：原生小程序 + 自建分层（`services/` 业务接口、`utils/` 请求/鉴权/离线队列、`mock/` 本地数据、`constants/` 枚举与错误码），自定义样式，无第三方 UI 框架。
- **页面（20）**：`home`（工单台）、`order/list|detail|checkin|dynamic-code|checklist|item-form|checkout|record-preview`、`common/watermark-camera|signature`、`fault/report|detail|list`、`mine/index|offline|password`、`unit/sign`（使用单位确认）、`login/index`。
- **Mock 开关**：`config/index.js` 的 `useMock: true` 为前端独立演示模式（无需后端）；联调时改 `false` 并指向后端，`services/` 签名不变。
- **登录方式**：账号密码登录（账号=维保单位分配的手机号）+ 登录后绑定微信；演示账号见 `mock/data.js`（密码统一 `123456`）。
- **离线能力**：`utils/offline.js` 持久化队列 + `app.js` 网络恢复自动补传（照片/签名断点续传）。

## 自建后端（backend/）

定位：小程序与监管平台之间的 **token 中控 + 接口转发层 + 业务落库**。平台凭证只存在于服务进程，前端永远不接触（详见 [backend/README.md](backend/README.md)）。

```bash
cd backend
mvn spring-boot:run        # 默认 8080；容器内由 Dockerfile 设 SERVER_PORT=80
mvn test                   # 184 例；pom 已为 surefire 加 -Djdk.attach.allowAttachSelf=true（受限环境必需）
```

## 监管平台对接（实测结论，勿按规范臆测）

- 业务根路径：`https://tzsb.scjgj.cq.gov.cn:1443/api/wlw/maintenance/`（网关 `/api/wlw` + 模块 `/maintenance`）。
- **2.1 登录实际为 GET + 查询串**（规范写的 POST + Body 是错的）；2.2—2.8 为 **POST + `x-www-form-urlencoded`**，2.3/2.4 为 multipart（带合同/证书 PDF）。
- 2.3 参数名实为 `useUnitName`；2.6 的 `workMan2Id` **必填**（单人作业也传第二人 ID）；2.7 **不支持**按 `elevatorCode` 查询，按 factoryNumber/registrationCode/deviceCode 查询。
- 2026-09-30 全链路实测：2.2 → 2.3 → 2.4 → 2.5 → 2.6 → 2.8 全部成功；2026-10-02 后端链路云端实测到达平台并返回真实校验错误。详见 [docs/07-full-test-20260930.md](docs/07-full-test-20260930.md)、[docs/07-平台联调实测记录.md](docs/07-平台联调实测记录.md)。
- **`originalRecordId` 幂等性仍未书面确认**：重放返回成功，是否产生重复记录需平台管理端核对；确认前后端**无自动重试实现**，也不得引入（AGENTS §2.3）。

## 凭证与安全

- `REG_*` 平台凭证一律配置在 `.env`（本地）/ 云托管「服务设置 → 环境变量」，**严禁入库**；`.env.example` 只有变量名与格式说明。
- JWT 签名密钥 `JWT_SECRET`（≥32 字节强随机）必须注入；代码内无默认值，缺失或空白即启动失败。
- 微信登录走 `wx.login` 的 code → 后端 `jscode2session` 换 openid：`WX_APPID` / `WX_APPSECRET` 必须注入，**不入库**；不配置时绑定/一键登录返回 `2001`。代码不信任 `X-WX-OPENID` 请求头，无 `dev_openid` 兜底。
- `GET /files/{id}` 必须带 URL 时效签名 `?s=`（`FileAccessTokenService`，默认 1 小时），库里只存不带签名的稳定地址。
- LBS 逆地址解析 key 同样不入库（`config/index.js` 留空，运行时经 storage/后端注入）。

## 云托管部署

- 以**根目录 Dockerfile** 构建（多阶段：admin dist → jar static），容器监听 80；部署后小程序经 `wx.cloud.callContainer` 调用（`config/index.js` 的 `cloud.env`/`cloud.service`），`wx.uploadFile` 需把域名加入 uploadFile 合法域名。
- 对象存储默认用云托管托管桶（桶名/地域非凭证，写入 `application.yml` 默认值），凭证走内网 `/_/cos/getauth`；仅自建 COS 桶联调时才注入 `COS_SECRET_ID/COS_SECRET_KEY`。
- 完整步骤与人工项见 [docs/11-云托管部署手册.md](docs/11-云托管部署手册.md)。

## Web 管理端（admin/）

```bash
cd admin
npm install
npm run dev        # http://localhost:5173，/api 代理到 localhost:8080（先启动后端）
npm run lint && npm test && npm run build   # 产物 dist/
```

角色与权限（后端强校验，非仅前端隐藏）：`LEADER` 只读；`ADMIN`/`SYS_ADMIN` 全部功能（档案维护、手动重报、触发平台同步）；`WORKER` 登录即拒（403）。演示账号（种子，密码均 `123456`）：

> 种子开关：环境变量 `SEED_DEMO_DATA`（别名 `DB_SEED_DEMO`），**默认 `false`**——生产首启不会写入演示账号与假电梯。

| 账号 | 角色 | 用途 |
|---|---|---|
| `13800000009` | ADMIN 维保部管理员 | 管理端全功能演示 |
| `13800000010` | SYS_ADMIN 系统管理员 | ADMIN 权限（SYS_ADMIN 专属功能已按 docs/01 §10.2 裁剪，角色保留兼容） |
| `13800000002` | LEADER 班组长 | 只读演示 |
| `13800000001` | WORKER 维保人员 | 小程序端（管理端登录被拒） |

> 班组数据权限（V7）：种子里 `13800000001`（张伟）/`13800000002`（陈刚）/`13800000004`（李强）同属「维保一班」；班组长见本组工单，组员仅见本人；工单写端点同样校验归属，越权 `1403` 且无副作用。**已建库环境需补班组**（种子仅在 `sys_employee` 为空时写入）：管理端「人员档案 → 班组」或 `UPDATE sys_employee SET group_name='维保一班' WHERE id IN ('emp_1','emp_2','emp_3');`

红线：管理端不提供平台凭证（`REG_*`）配置界面（AGENTS §2.1）；不提供批量自动重试入口，重报仅逐条人工触发（AGENTS §2.3）。

## 仓库约定（开发者）

- `npm run lint`（根目录）只校验小程序代码；`admin/` 与 `backend/` 各自在子目录内 lint/test（根 `.eslintignore` 已排除二者，避免未装 admin 依赖时根 lint 失败）。
- 接口改动三处同步：`services/` + `mock/server.js` + `docs/04` A 部分；平台调用一律以 `docs/07` 实测为准。
- 详细规则见 [AGENTS.md](AGENTS.md)。

## 相关文档索引

| 文档 | 内容 |
|---|---|
| [docs/01-需求文档.md](docs/01-需求文档.md) | 需求基准（V3.4，含 §10 范围裁剪清单） |
| [docs/02-技术架构文档.md](docs/02-技术架构文档.md) | 目标架构与当前实际形态对照 |
| [docs/03-UI设计文档.md](docs/03-UI设计文档.md) | 设计规范、页面清单、真机验收清单 |
| [docs/04-接口文档.md](docs/04-接口文档.md) | A. 内部 API（100 条业务路由）＋ B. 平台 V1.5 对接实测修订 |
| [docs/05-测试与验收方案.md](docs/05-测试与验收方案.md) | 测试策略、§10.4/§10.4A 人工回归清单、上线前检查表 |
| [docs/06 / 06b](docs/06-平台接口确认清单.md) | 平台待确认清单、沟通话术 |
| [docs/07 / 07-full-test](docs/07-平台联调实测记录.md) | 平台联调实测记录（2026-09-30 全链路打通） |
| [docs/08-项目审查与开发计划.md](docs/08-项目审查与开发计划.md) | 现状审查与 P0—P5 阶段（历史口径） |
| [docs/09-管理端设计.md](docs/09-管理端设计.md) | 管理端设计与落地记录 |
| [docs/10-管理端验收清单.md](docs/10-管理端验收清单.md) | 管理端人工验收清单（角色矩阵/功能/红线） |
| [docs/11-云托管部署手册.md](docs/11-云托管部署手册.md) | 云托管部署步骤与人工项 |
| [docs/12-未完成事项清单.md](docs/12-未完成事项清单.md) | **待办唯一清单**（外部依赖/人工验收/功能缺口/工程质量/文档一致性） |
| [AGENTS.md](AGENTS.md) | 项目规则（分层/安全红线/编码/提交/文档同步） |
