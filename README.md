# 智慧特种设备维保系统（电梯无纸化维保）

> 面向电梯维保单位的一体化作业系统：微信小程序现场作业 + Spring Boot 后端 + Vue3 管理端，覆盖派单、签到、清单、签退、归档与监管平台上报。
>
> **最后更新**：2026-11-10　**当前状态**：主链路可演示、可联调；上线门禁与剩余事项见 [docs/12-未完成事项清单.md](docs/12-未完成事项清单.md)。

## 1. 业务闭环

```text
计划派单 / 手动派单
  → 主维保签到（GPS 定位；配合人员使用双人动态码）
  → 作业清单（周期项可跳过；关键项按模板要求强制照片）
  → 签退（作业时长、有配合人员时双签名、异常项 S0—S7 判定）
  → 冻结维保记录 + 上报监管平台 2.6 + 按需导出 PDF
  → 使用单位签字确认 / 管理端归档与检索
```

合规依据：TSG T5002《电梯维护保养规则》与重庆市监管平台《电梯维保记录上报接口规范 V1.5》；平台实测结论以 [docs/07](docs/07-平台联调实测记录.md) 为准。

## 2. 项目组成

| 部分 | 位置 | 技术与现状 |
|---|---|---|
| 微信小程序 | 仓库根目录（微信开发者工具打开） | 原生小程序，22 页、9 个 services、47 条 mock 路由；支持 mock 独立演示或直连后端 |
| 自建后端 | `backend/` | Java 17 + Spring Boot 3.3.4 + MyBatis-Plus + MySQL 8 + Flyway V1—V22；101 条业务路由 + 3 个探针；196 个单测 |
| Web 管理端 | `admin/` | Vue3 + Vite + Element Plus；17 个视图（14 条业务路由 + 登录/403/404）；9 个 Vitest 用例 |
| 联调与回归脚本 | `scripts/` | 平台 2.1—2.8 联调 PowerShell、派单/模板回归 Node 脚本 |
| 项目文档 | `docs/` | 需求、架构、UI、接口、测试、平台确认、联调、计划、管理端、部署、待办（01—12） |
| 平台规范原文 | `doc_text.txt` | 平台接口规范 V1.5 原文提取，供 `docs/04` B 部分逐字核对（只读） |

`miniapp/` 为微信开发者工具“多端应用”生成物，已 gitignore，不属于本项目交付物。

## 3. 核心能力

- **派单**：每日 09:00 自动扫描到期电梯，每 13 分钟兜底自愈；同项目同日到期一次性全部派单。到期基线按维保类型取最近一次已完成维保，首保/补单支持手动派单。读接口不触发派单。
- **签到**：主维保与配合人员分别签到；坐标必填，按电梯级→品种级→全局阈值做 Haversine 校验；电梯无坐标时留痕 `UNKNOWN` 并以现场定位回填 `SELF_COLLECTED`。
- **双人动态码**：5 秒步长、60 秒有效、绑定工单、一次性消费；配合人员签到用原子 SQL 消费，校验失败不产生任何副作用。
- **作业清单**：按维保类别加载 TSG 附件 A—D 模板；周期项可标记“本次不执行”；关键项按模板要求强制照片留证；异常项必须显式记录 S0—S7。
- **签退**：校验检查项完整性、关键项照片、作业时长（默认 ≥30 分钟）、主维保签名，有配合人员时校验双签名；冻结检查项与上报快照后自动转发平台 2.6。
- **设备信息**：小程序电梯列表/档案，仅显示本人负责的电梯；支持现场更正“项目名称（小区）”，与使用单位名称严格区分。
- **急修闭环**：急修单登记、列表、详情、派单、关闭与 PDF 导出。
- **使用单位**：维保记录签字确认页，支持使用单位安全管理员确认。
- **管理端**：看板、工单、上报异常、急修单、上报日志、计划调度、模板管理、电梯台账、单位/人员/电梯档案、平台人员登记。
- **离线能力**：工单快照本地缓存 + 持久化补传队列，网络恢复自动补传照片/签名与检查项。
- **消息**：站内消息中心；真实微信订阅消息推送尚未接入（见 docs/12 C-01）。

## 4. 仓库结构

```text
├─ app.js / app.json / app.wxss   小程序入口（22 页，tabBar 4 个）
├─ pages/                         小程序页面（每页 .js/.json/.wxml/.wxss 四件套）
├─ services/                      小程序业务接口层（9 个模块）
├─ utils/request.js               统一请求封装（Bearer、幂等键、401、callContainer/wx.request 分流）
├─ utils/offline.js               离线快照与补传队列
├─ utils/auth.js / guard.js       登录态与页面守卫
├─ config/index.js                小程序环境配置（ENV、useMock、cloud、上传超时）
├─ constants/index.js             枚举、错误码、业务常量
├─ mock/                          mock 契约层（server.js 路由 = docs/04 A 部分）
├─ backend/                       Spring Boot 后端（Flyway、平台转发、文件、PDF、定时派单）
├─ admin/                         Vue3 管理端（看板/工单/档案/调度/模板/台账）
├─ scripts/                       联调与回归脚本（fixtures/ 为合成测试件）
├─ docs/                          01—12 编号文档
├─ doc_text.txt                   平台规范 V1.5 原文提取（只读）
├─ Dockerfile                     云托管单容器构建（管理端 dist + 后端 jar）
└─ README.md / AGENTS.md          项目说明与开发规则
```

开发约定（完整规则见 [AGENTS.md](AGENTS.md)）：

- 分层固定为 页面 → `services/*` → `utils/request`；页面不直接发请求；
- 新增/修改接口须三处同步：`services/`、`mock/server.js`、`docs/04` A 部分；
- 提交前按改动范围跑根 `npm run lint`、`admin/` 三件套、`backend/` `mvn test`；
- 凭证、真实合同/证书、trace/HAR、`.env` 一律不入库；
- 待办统一维护在 [docs/12](docs/12-未完成事项清单.md)。

## 5. 快速开始

### 5.1 前置条件

- JDK 17、Maven 3.9+
- MySQL 8.0（本地库名 `maintenance`，Flyway 自动建表）
- Node.js 18+（建议 20 LTS）
- 微信开发者工具

### 5.2 启动后端

```bash
# 1) 准备本地环境变量（只在本机 .env，严禁入库）
cp .env.example .env
# 至少填写：DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD / JWT_SECRET
# 需要演示数据时首次启动设 SEED_DEMO_DATA=true，播种完成后改回 false

# 2) 从仓库根目录启动（加载根目录 .env；-f 指定 backend/pom.xml）
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.workingDirectory=.
# 健康检查：http://localhost:8080/health  →  {"code":0,"data":{"status":"UP"}}
```

`JWT_SECRET` 必须为 ≥32 字节强随机值；代码中没有默认密钥，缺失或空白会直接启动失败。

> 若在 `backend/` 目录直接执行 `mvn spring-boot:run`，Spring 读取的是 `backend/.env`，不会读取根目录 `.env`；两种启动方式二选一，不要把同一份凭证复制成两份。

### 5.3 启动管理端

```bash
cd admin
npm ci
npm run dev
# http://localhost:5173 ，/api 由 Vite 代理到 http://localhost:8080
```

### 5.4 运行小程序

1. 微信开发者工具打开仓库根目录。
2. 本地后端联调：把 `config/index.js` 改为 `ENV = 'dev'`、`useMock = false`，并在开发者工具中勾选“不校验合法域名”。
3. 纯前端演示：设 `useMock = true`，无需启动后端，所有接口走 `mock/server.js`。
4. 云托管联调：`ENV = 'prod'`，由 `config.cloud.enabled` 控制是否走 `wx.cloud.callContainer`；关闭时回退 `wx.request` 直连 `apiBaseUrl`。

上传图片使用 `wx.uploadFile`（callContainer 只接收 JSON）；云托管部署时需把服务域名加入小程序后台的 **uploadFile 合法域名**。

### 5.5 演示账号

小程序登录方式为手机号（账号）+ 密码；登录成功后尝试自动 `wx.login` 绑定 openid（绑定失败不阻断登录），微信一键登录契约保留但暂无独立页面入口。

`SEED_DEMO_DATA=true` 首次启动会播种以下账号，密码统一 `123456`：

| 账号 | 角色 | 用途 |
|---|---|---|
| `13800000009` | ADMIN 维保部管理员 | 管理端全功能 |
| `13800000010` | SYS_ADMIN 系统管理员 | 管理端全功能（系统管理模块已按裁剪范围移除） |
| `13800000002` | LEADER 班组长 | 管理端只读 |
| `13800000001` | WORKER 维保人员 | 小程序作业（主维保） |
| `13800000004` | WORKER 维保人员 | 小程序作业（配合人员） |

> 管理端登录会带 `client=admin` 做角色白名单校验，`WORKER` / `UNIT_ADMIN` 登录管理端返回 403。使用单位签字页为免登录分享链接（`rid + token`），无需 UNIT_ADMIN 账号登录小程序。

班组数据权限：演示账号 `13800000001` / `13800000002` / `13800000004` 同属「维保一班」；种子仅在 `sys_employee` 为空时写入。已建库环境若缺少班组，班组长工单列表会为空，需在管理端「人员档案 → 班组」补齐。工单读写均校验班组归属，越权返回 `1403` 且不产生副作用。

## 6. 配置与环境变量

### 6.1 后端环境变量

本地放在根目录 `.env`（已 gitignore），云托管放在“服务设置 → 环境变量”。`.env.example` 只有变量名与格式说明。

| 变量 | 说明 |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | MySQL 连接；也兼容 `DB_USERNAME` |
| `JWT_SECRET` | 自建 JWT 签名密钥，≥32 字节，必填；无默认值 |
| `JWT_EXPIRE_HOURS` | 会话有效期，默认 72 小时 |
| `WX_APPID` / `WX_APPSECRET` | 微信 `jscode2session` 换取 openid；未配置时绑定/一键登录返回 `2001` |
| `REG_AUTH_LOGIN_URL` / `REG_API_BASE_URL` | 平台登录地址与业务根路径 |
| `REG_USERNAME` / `REG_KEY` / `REG_APPCODE` / `REG_SECRET` | 平台凭证，只允许环境变量 |
| `REG_LEGACY_UPLOAD_ENABLED` | 平台 2.8 存量记录提交开关，默认 `false` |
| `SEED_DEMO_DATA` | 演示种子开关（别名 `DB_SEED_DEMO`），生产必须 `false` |
| `APP_WORK_DURATION_MINUTES` | 签到→签退最短作业时长，默认 30 |
| `APP_CHECKIN_THRESHOLD_METERS` | 签到定位全局阈值（米），默认 200 |
| `COS_BUCKET` / `COS_REGION` / `COS_AUTH_URL` | 对象存储；云托管托管桶走内网临时凭证 |
| `COS_SECRET_ID` / `COS_SECRET_KEY` | 仅自建 COS 桶联调时使用 |
| `LBS_AMAP_KEY` / `LBS_TENCENT_KEY` | 逆地址解析 key，不入库 |
| `FILE_STORAGE_DIR` / `APP_FILE_ROOT` | 本地文件目录（COS 模式下作为写穿缓存） |
| `APP_PUBLIC_BASE_URL` | 反向代理/子路径部署时用于拼接文件 URL |
| `SERVER_PORT` | 本地默认 8080；容器内固定 80 |

> **自动重试未实现**：`originalRecordId` 幂等性未经平台书面确认前，后端没有自动重试逻辑；上报失败只保留状态并支持逐条人工重报。

### 6.2 小程序配置

`config/index.js`：

- `ENV`：`dev` 走本地后端，`prod` 走云托管/线上地址。
- `useMock`：`true` 全部走 `mock/server.js`；`false` 走真实请求。
- `cloud.enabled` / `cloud.env` / `cloud.service`：云托管 `wx.cloud.callContainer` 开关与环境/服务名。
- `minWorkDurationMinutes`：前端签退时长校验，默认 30；测试时可临时调小，回归前恢复。

### 6.3 管理端配置

- 开发期：Vite 将 `/api` 代理到 `http://localhost:8080`。
- 生产：`admin/dist` 由 Dockerfile 打进后端 jar 的静态目录，同域访问；也可用 `VITE_API_BASE` 指定 API 前缀。

## 7. 三端说明

### 7.1 小程序（22 页）

| 模块 | 页面 |
|---|---|
| 工作台/工单 | `home/index`、`order/list`、`order/detail`、`order/checkin`、`order/dynamic-code`、`order/checklist`、`order/item-form`、`order/checkout`、`order/record-preview` |
| 公共能力 | `common/watermark-camera`、`common/signature` |
| 急修 | `fault/report`、`fault/detail`、`fault/list` |
| 设备信息 | `elevator/list`、`elevator/detail` |
| 我的 | `mine/index`、`mine/offline`、`mine/password` |
| 使用单位 | `unit/sign` |
| 其他 | `login/index`、`message/index` |

工程约定：

- 页面只调 `services/*`，不直接 `wx.request` / `wx.uploadFile`；
- 所有 POST/PUT/PATCH 自动带 `X-Idempotency-Key`；
- 401 清登录态并跳登录页；
- 工单数据可本地快照，断网时先写本地并在网络恢复后补传；
- 定位权限仅用于签到，`requiredPrivateInfos` 已声明 `getLocation`。

### 7.2 后端（`backend/`）

- 统一响应 `{code, message, data}`，业务码表见 `docs/04` A 部分。
- JWT 鉴权；平台凭证只存在后端进程，不向前端下发。
- Flyway 自动迁移；MySQL 库不存在时由 JDBC `createDatabaseIfNotExist` 自动创建。
- 平台 token 进程内缓存，TTL = `expires_in − 60s`；401 清缓存重登重试 1 次。
- 文件：`/files/upload` 代理上传 COS（或本地磁盘）；`GET /files/{id}` 必须带 `?s=` 时效签名，默认 1 小时；COS 模式本地磁盘为写穿/写回缓存。
- PDF：管理端维保记录/急修单可导出 PDF。
- 定时任务：每日 09:00 自动派单 + 每 13 分钟兜底；只由定时任务驱动，读接口不产生写副作用。

```bash
cd backend
mvn test                 # 196 个单测；surefire 已配置 -Djdk.attach.allowAttachSelf=true
# 本地启动见 §5.2（从仓库根目录加载根 .env）
```

### 7.3 管理端（`admin/`）

| 模块 | 视图 |
|---|---|
| 监控 | 看板 |
| 工单 | 工单列表、工单详情、上报异常清单、上报日志 |
| 急修 | 急修单 |
| 计划 | 计划调度、模板管理、电梯台账 |
| 档案 | 维保单位、使用单位、人员、电梯 |
| 平台人员 | 平台名单比对与登记 |

角色与权限（后端强校验）：

- `LEADER`：只读；
- `ADMIN` / `SYS_ADMIN`：读写全功能；
- `WORKER` / `UNIT_ADMIN`：管理端登录 403。

```bash
cd admin
npm run lint
npm test
npm run build
```

Windows 下若 esbuild 报 `Access is denied`，先把 `TMP`/`TEMP` 指向工作区内目录（如 `admin/.tmp-build`）再构建。

## 8. 监管平台对接（实测优先）

- 业务根路径：`https://tzsb.scjgj.cq.gov.cn:1443/api/wlw/maintenance/`；除登录外全部接口需 `Authorization: Bearer`。
- **2.1 登录**：实际为 **GET + URL 查询串**（规范原文的 POST + Body 有误）。
- **2.2—2.8**：POST + `application/x-www-form-urlencoded`；2.3/2.4 为 **multipart + 文件**（`contractFile` / `certificateFile`）。
- **2.3**：参数名实为 `useUnitName`。
- **2.6**：`workMan2Id` **必填**，单人作业也要传第二人 ID。
- **2.7**：不支持按 `elevatorCode` 查询；按 `factoryNumber` / `registrationCode` / `deviceCode` 查询，命中后回填未脱敏的 `elevatorAdminister*` / `emergencyPhone`。
- **2.8**：存量记录提交由 `REG_LEGACY_UPLOAD_ENABLED` 控制，默认关闭。
- **token**：JWT，`expires_in ≈ 3599`，缓存 TTL = `expires_in − 60s`；401 清缓存重登重试 1 次。
- **幂等**：`originalRecordId` 是否真正幂等尚未经平台书面确认；确认前**不得开启自动重试**。

完整实测记录见 [docs/07-平台联调实测记录.md](docs/07-平台联调实测记录.md) 与 [docs/07-full-test-20260930.md](docs/07-full-test-20260930.md)。

## 9. 部署（微信云托管）

```text
小程序 ──► https://<域名>/api/* ──► 云托管容器（Spring Boot :80）
管理端 ──► https://<域名>/*     ──► 同容器静态资源（admin/dist）
                              └──► 云 MySQL（内网，Flyway 自动建表）
```

- 根目录 `Dockerfile` 多阶段构建：Node 20 构建管理端 → Maven 打包后端 → JRE 17 Alpine 运行。
- 容器监听 `80`，健康检查 `GET /health`。
- 小程序云托管调用：`wx.cloud.callContainer`（环境 ID/服务名见 `config/index.js` 的 `cloud` 配置）；图片上传仍需配置 uploadFile 合法域名。
- 对象存储：默认使用云托管托管桶；自建 COS 桶才需要 `COS_SECRET_ID/KEY`。
- 生产环境必须设置 `SEED_DEMO_DATA=false`、强 `JWT_SECRET`，并注入 `WX_APPID/WX_APPSECRET` 与平台凭证。

完整步骤与人工项见 [docs/11-云托管部署手册.md](docs/11-云托管部署手册.md)。

## 10. 安全与合规

- 平台凭证 `REG_*`、`WX_APPSECRET`、`JWT_SECRET`、COS 密钥、LBS key **只允许**存在于本地 `.env` 或云托管环境变量，严禁入库、写入文档/注释或下发前端。
- openid 只接受 `wx.login` code 经后端 `jscode2session` 换取；不信任 `X-WX-OPENID` 请求头，无 `dev_openid` 兜底。
- 文件读取必须带 URL 时效签名；数据库只存不带签名的稳定地址。
- 日志脱敏：不打印完整 token、手机号、openid、平台密钥。
- 管理端不提供平台凭证配置界面，也不提供批量自动重试入口。
- 合规底线：任何可能导致监管平台出现错误/虚假数据的缺陷一律按 Blocker 处理（详见 `docs/05` §9）。
- 上线前仍需完成 [docs/12](docs/12-未完成事项清单.md) 的 P0 门禁：幂等书面确认、真实凭证复跑、30 分钟时长口径、三端人工验收、订阅消息、4 年留存归档。

## 11. 测试与 CI

| 范围 | 命令 |
|---|---|
| 小程序（根目录） | `npm run lint` |
| 管理端 | `cd admin && npm run lint && npm test && npm run build` |
| 后端 | `cd backend && mvn test` |
| 派单回归 | `node scripts/verify-dispatch.js`（6 台同日到期 → 当日全部 09:00 派单） |
| 模板回归 | `node scripts/verify-templates.js` |
| 平台联调 | `scripts/*.ps1`（写操作会产生平台真实数据，执行前确认） |

根 `npm run lint` 只覆盖小程序代码；`admin/`、`backend/` 在各自目录校验。GitHub Actions 当前只运行后端 `mvn test`；根 lint、管理端测试/构建与 verify 脚本仍需在本地执行。

## 12. 文档索引

| 文档 | 内容 | 版本 |
|---|---|---|
| [docs/01-需求文档.md](docs/01-需求文档.md) | 需求基准、TSG 条款对照、范围裁剪 | V3.6 |
| [docs/02-技术架构文档.md](docs/02-技术架构文档.md) | 目标架构与当前实现对照 | V1.10 |
| [docs/03-UI设计文档.md](docs/03-UI设计文档.md) | 设计规范、页面清单、真机验收清单 | V2.8 |
| [docs/04-接口文档.md](docs/04-接口文档.md) | A. 内部 API；B. 平台 V1.5 实测修订 | V2.36 |
| [docs/05-测试与验收方案.md](docs/05-测试与验收方案.md) | 测试策略、人工回归清单、上线检查表 | V1.9 |
| [docs/06-平台接口确认清单.md](docs/06-平台接口确认清单.md) / [docs/06b-平台对接沟通话术.md](docs/06b-平台对接沟通话术.md) | 平台待确认项与沟通话术 | — |
| [docs/07-平台联调实测记录.md](docs/07-平台联调实测记录.md) | 平台联调实测记录（2026-09-30 全链路） | V1.5 |
| [docs/08-项目审查与开发计划.md](docs/08-项目审查与开发计划.md) | 阶段、历史与审查结论 | V3.7 |
| [docs/09-管理端设计.md](docs/09-管理端设计.md) | 管理端设计、页面与权限 | V3.14 |
| [docs/10-管理端验收清单.md](docs/10-管理端验收清单.md) | 管理端人工验收清单 | V1.7 |
| [docs/11-云托管部署手册.md](docs/11-云托管部署手册.md) | 云托管部署步骤与人工项 | — |
| [docs/12-未完成事项清单.md](docs/12-未完成事项清单.md) | **待办唯一清单**（含 P0 上线门禁） | V1.0 |
| [AGENTS.md](AGENTS.md) | 项目规则（分层/安全红线/编码/提交/文档同步） | V3.0 |

## 13. 许可证

[MIT](LICENSE)
