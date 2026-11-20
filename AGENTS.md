# 项目规则（AGENTS.md）

> 电梯无纸化维保系统——编码与协作规则。AI 助手与人工开发者均须遵守。
> 创建：2026-09-30。V3.0 重写：2026-11-10（规则变更须在 `docs/AGENTS-修订记录归档.md` 追加修订记录，见 §10）。
> 文末 AOCI 托管块（以 `aoci:begin` / `aoci:end` 标记）由工具维护；除 AOCI 工具外不得手工增删改。

## 0. 开工前先读什么

| 要解决的问题 | 先读 | 说明 |
|---|---|---|
| 需求与合规依据 | `docs/01-需求文档.md` | TSG T5002、平台规范 V1.5 逐字核对；范围裁剪见 docs/01 §10 |
| 平台接口怎么调 | `docs/07-平台联调实测记录.md`、`docs/07-full-test-20260930.md` | **实测优先于规范**；冲突记入 `docs/06` |
| 内部 API 契约 | `docs/04-接口文档.md` A 部分、`mock/server.js` | 新增/修改接口三处同步（含 `services/`） |
| 还剩什么没完成 | `docs/12-未完成事项清单.md` | **待办唯一清单**（含 P0 上线门禁） |
| 当前阶段与历史 | `docs/08-项目审查与开发计划.md` | 只作背景与追溯，不当作待办 |
| 管理端设计与验收 | `docs/09-管理端设计.md`、`docs/10-管理端验收清单.md` | 角色矩阵与人工验收证据 |
| 维保记录/急修单表单版式 | `docs/13-维保与急修记录表单设计.md` | 两张导出 PDF 的逐格版面口径、字段来源与采集缺口 |
| 安装移交检查清单/整改清单版式 | `docs/15-安装移交维保检查清单表单设计.md` | GB/T 10060—2023 表 1 的 74 项检查表、不合格项 1:1 自动映射整改、判定规则、签字审批与一梯一档归档目录 |
| 账号与数据权限 | `docs/14-账号权限与组织角色设计.md` | 公司内部三条线（平台管理/安装部/维保部）角色码、数据域与权限矩阵；使用单位为外部客户租户（docs/14 V2.1 客户租户模型，非公司部门） |
| 测试与上线检查 | `docs/05-测试与验收方案.md`、`docs/11-云托管部署手册.md` | 人工验收结论写回 docs/05、docs/10 |

**当前基线（2026-11-19 统一登录 + 管理端去演示化 + P3 GRACE/迁移补全后实测）**：小程序 49 页（主包 19 —— 含 `pages/client/select-unit` 客户选单位页；+ 4 子包 pkg-handover 12/pkg-maintenance 7/pkg-client 4/pkg-admin 7；无原生 tabBar，custom-tabbar 菜单驱动）；`services/` 14 个模块（含 menu/admin/client）；`mock/server.js` 124 条路由；后端 Flyway V1—V54（V48 client_account + 客户签字快照、V49 审计 use_unit_id、V50/V54 存量客户账号迁移）、709 个单测（以 `mvn test` 实测为准）；管理端 29 个视图 + 组件廊（自建组件库，Element Plus 已移除；**已删除全部原型演示数据**：无 demo-personas/prototype-mock/provider，角色由服务端下发且不可切换）；文档 01—16。**登录只有一个小程序入口**：`POST /auth/login` 按账号自动识别员工/客户，前端不出现通道切换。计数用于快速定位，提交前以代码实测为准；文档数字滞后时改文档，不要为迁就旧数字改代码。

## 1. 系统组成与分层

```
├─ app.js / app.json / app.wxss   小程序入口（48 页：主包 18 + 4 子包；无原生 tabBar，custom-tabbar 菜单驱动；页面口径见 docs/03 §3.5）
├─ pages/                         页面（每页 .js/.json/.wxml/.wxss 四件套）
├─ services/                      业务接口层：页面只调 services，禁止直接 wx.request / wx.uploadFile
├─ utils/request.js               唯一请求封装（Bearer、X-Idempotency-Key、401、callContainer/wx.request 分流）
├─ utils/offline.js               离线快照与持久化补传队列（网络恢复自动补传）
├─ config/index.js                环境配置（ENV、useMock、apiBaseUrl、cloud.env/service、LBS key 留空）
├─ constants/index.js             枚举/错误码/业务常量（文案统一从这里取）
├─ mock/                          mock 契约层（server.js 路由 = docs/04 A 部分）
├─ backend/                       Spring Boot 3.3.4 后端（Flyway V1—V54、JWT/BCrypt、平台转发、701 单测；P3 客户账号独立体系）
├─ admin/                         Vue3 + Vite + 自建组件库（ui/）管理端（23 视图，Element Plus 已移除；lint/vitest/build）
├─ scripts/                       平台联调脚本与回归脚本（fixtures/ 合成测试件；tmp/ 已 gitignore）
├─ doc_text.txt                   平台规范 V1.5 原文提取（docs/04 B 部分核对来源，只读）
├─ docs/                          01—15 编号文档（见 §8）
└─ miniapp/                       微信开发者工具多端生成物（本地，已 gitignore，不属于交付物）
```

**分层规则**：

1. 页面 → `services/*` → `utils/request`；页面不得直接发起网络请求，也不得直接读 storage 里的业务数据（离线快照统一走 `utils/offline.js`）。
2. 新增或修改接口**三处同步**：`services/` 方法、`mock/server.js` 路由、`docs/04` A 部分条目；缺一不可。
3. `services/` 方法签名不得因 mock↔真实切换而变化；`useMock` 只在 `request.js` 分流。
4. 后端实现须与 `docs/04` B 部分实测结论一致，不得按 V1.5 规范原文实现已被实测推翻的调用方式（如 2.1 用 POST）。
5. 生产默认走云托管 `wx.cloud.callContainer`（`config.cloud`）；上传因 callContainer 只收 JSON 仍走 `services/upload.js` 的 `wx.uploadFile`。两条链路必须保持同一响应口径 `{code,message,data}`。

## 2. 安全红线（最高优先级）

1. **平台凭证 `REG_*`（username/key/appcode/secret）严禁**写入代码、mock、文档、注释、`container.config.json` 或任何入库文件；只允许存在于 `.env`（本地，已 gitignore）与云托管环境变量。`.env.example` 只留变量名与格式说明。
2. **同类凭证一律不入库**：LBS key、`WX_APPSECRET`、`JWT_SECRET`、`COS_SECRET_ID/KEY`。CI 工作流中的占位密钥必须标注“仅 CI”，不得复用到任何真实环境。
3. **openid 只信服务端换取**：只接受 `wx.login` 的 code → 后端 `jscode2session`；禁止信任 `X-WX-OPENID` 请求头，禁止 `dev_openid` 兜底。`JWT_SECRET` 无默认值，缺失/空白即启动失败。
4. **`originalRecordId` 幂等性未经平台书面确认前，禁止任何自动重试**：后端无自动重试实现；仅允许对 `FAILED` 记录逐条人工重报。不得新增批量重试端点、管理端批量入口或定时补偿任务。
5. **文件访问必须带时效签名**：`GET /files/{id}` 校验 `?s=`；数据库只存不带签名的稳定 URL，读路径返回重签后的新鲜 URL；不得把签名或 token 写入日志。
6. **日志脱敏**：不打印完整 token、手机号、openid、平台密钥；上报报文与 `reg_upload_log` 按脱敏口径落库。
7. **敏感文件不入库**：`.env`、真实合同/证书 PDF、`scripts/tmp/`、trace/HAR、`reg-*.json`。`.aoci/`、`opencode.json`、`miniapp/` 为本地工具/生成物，保持 gitignore。
8. **管理端不提供**平台凭证（`REG_*`）配置界面，也不提供批量自动重试入口。

## 3. 关键业务不变量（改动前先确认）

1. **工单归属**：工单详情/清单/签到/检查项/签退/签退附件等读写端点，统一使用 V7 班组数据权限谓词；越权一律 `1403` 且不得产生副作用。电梯列表/档案/项目名称更正同样只允许本人负责的电梯。绕过归属校验等于代签到/代签退，签退会自动上报 2.6，属于向监管平台写入虚假数据（Blocker）。
2. **签到**：定位坐标必填（缺失 `422`），严禁伪造坐标兜底；电梯有坐标时按 Haversine 距离与三级阈值（电梯级→品种级→全局）拦截 `1001`；电梯坐标缺失时只留痕 `UNKNOWN` 不拦截，并以首个签到者定位回填 `SELF_COLLECTED`（仅留痕，不作围栏依据）。主维保与配合人员分别留痕、互不覆盖，重复签到 `1003`。
3. **双人动态码**：`HMAC(密钥, 工单ID|时间步长)` 派生，5 秒步长、60 秒有效、绑定工单。配合人员签到消费时使用 `consumeDynamicCodeIfUnused` 原子更新（判空+写入单条 SQL）；`/dynamic-code/verify` 只校验不消费。校验/消费必须发生在任何写操作之前，失败不得落库或改状态。
4. **清单与签退**：周期性条目 `notInThisRun=true` 不计入必填；其余条目必须有结果。关键项且 `photoRequired` 的条目至少 1 张照片（`NA` 除外）。签退要求 `PROCESSING`、作业时长 ≥ 30 分钟（`APP_WORK_DURATION_MINUTES` / 前端 `config.minWorkDurationMinutes` 双保险）、主维保签名必填、有配合人员时双人签名。异常项必须显式记录 S0—S7（S0 也是合法值）；仅当没有任何异常项时才自动补 S0，禁止对异常项静默补 S0。
5. **上报快照**：签退时冻结检查项与 `reportPayload` 快照，2.6 使用快照上报，不重新组装；签退后自动转发，失败只标记状态，后续仅逐条人工重报。
6. **派单**：自动派单只由定时任务驱动（每日 09:00 主扫描 + 每 13 分钟兜底自愈；判定到期前一日，计划时间=到期日 09:00），读接口一律不触发派单。到期基线按类型取最近一次同类型已完成维保；该类型从未执行时，以“首次维保日期”（最早一次 DONE 签退）起算，无任何已完成维保时回退建档 `lastMaintenanceAt`。首保/补单走手动派单；同项目同日到期电梯应一次性全部派单。
7. **文件存储**：配置 COS 时 COS 为唯一持久化源，本地磁盘是写穿/写回缓存（读本地→未命中回源 COS→写回）；未配置 COS 时本地磁盘仅为开发模式，容器重建即丢失。上传响应与所有读路径统一经 `FileStorageService.urlOf` 重签。
8. **工单号**：只经 `OrderNoIssuer` 发号；唯一键冲突重试时必须**重新统计当日工单数**并取“计数+1”，不得把重试次数当偏移跳过空闲号。
9. **单账号单会话（多角色）**：一个手机号 = 一个账号，同一时刻一个会话令牌（`em_token`）；账号可持多角色（`sys_user_role`），权限取**并集**，数据范围逐角色判断（ScopeResolver 读写谓词，写绝不扩区域/部门）。`activeWorkspace` 决定工作台与 Tab 渲染，可在「我的」切换（重拉 `GET /me/menus?workspace=`）；登录即清旧会话。门户账号经 V41 并入员工体系，`unit_token` 双令牌体系废除（`/unit/login*` 废弃保留契约标注至生产清库）。微信绑定为可选能力，入口只在「我的」页，未开启时登录页不出现微信入口。页面只调 services，跳转走 `utils/nav` 白名单 + `utils/guard` 四层守卫（登录态→角色→权限码→单据状态）。**客户主体（权限 P1，生效角色全为 CUSTOMER，混合角色按员工）**另受三层收敛：①**路由白名单**——客户令牌仅可达 `/unit/**` + 自身账号端点（菜单/本人/改密/绑微信/登出）+ `GET /elevators*`、`GET /files/*`，内部/管理端点一律 403；②**记录签字状态门禁**——可见={PENDING_CLIENT_SIGNATURE,SIGNED,ARCHIVED,REFUSED_RETURNED}+链末端最新版本，可签=唯一 PENDING_CLIENT_SIGNATURE+confirmStatus=PENDING，条件更新先签为准，签字/拒签不触发 2.6；③**字段白名单**——客户投影 `toClientMap` 禁返 reportPayload/context/shareToken/平台ID/refuseReason/satisfaction（兼容期仅 `/unit/pending-signs` 行内保留 shareToken，P3 移除）；客户矩阵最小集 `client:elevator_view/sign/download` 三码，客户工作台菜单收敛「我的电梯+待办签字」（报修/评价/合同/移交入口下线）。详见 docs/14 §4.6/§4.7、docs/04 A.12.1。

## 4. 编码约定

### 4.1 小程序

- CommonJS（`require`/`module.exports`），ES2018，2 空格缩进，单引号；提交前根目录 `npm run lint` 必须通过。
- 命名：services 方法动词开头（`getXxx/createXxx/submitXxx/uploadXxx`）；常量 UPPER_SNAKE；页面 data 字段与 `docs/04` 一致。
- 每个文件顶部一行职责说明；涉及平台/合规的约定注明出处（如 `docs/01 §3.17`、平台 2.6）。
- 错误码与文案统一用 `constants/index.js` 的 `ERROR_CODES`/`TOAST_TEXT`/`EMPTY_TEXT`，禁止页面硬编码。
- 枚举只从 `constants` 引用：维保类别 FM/HM/TM/SM/OY、隐患码 S0—S7、判定方式 NUMERIC/STANDARD/MANUFACTURER/QUALITATIVE。
- 时间存取一律 `yyyy-MM-dd HH:mm:ss`（GMT+8），用 `utils/util.js` 的 formatTime/parseTime；时长 `HH:mm:ss`。
- 所有 POST/PUT/PATCH 自动携带 `X-Idempotency-Key`（`request.js` 已实现），新写接口不得绕过。
- 新页面四件套齐全并注册进 `app.json`；`requiredPrivateInfos` 变更须同步说明用途；不得引入新 UI 框架（TDesign 已移除，重新引入须按 docs/08 评估并同步文档）。
- 照片/签名统一走 `services/upload.js` 与 `pages/common/watermark-camera|signature`，不在页面内另写上传逻辑。

### 4.2 后端

- Java 17 / Spring Boot 3.3.4；统一 `ApiResponse` 返回；配置经 `PlatformProperties`/`AppProperties` 从环境变量注入；容器端口 80（`SERVER_PORT`）。
- MyBatis-Plus 持久化；Flyway 迁移只追加新版本（V23、V24…），**不得修改已在环境执行过的迁移文件**。
- 写操作先做归属/状态/参数校验，再落库；校验失败不得留下半成品状态。
- 平台 token 缓存 TTL = `expires_in − 60s`；401 清缓存重登重试 1 次；平台调用异常按 `docs/04` B.9 码表处理。
- 单测与实现同包路径；改业务规则必须补对应测试（尤其签到/签退/动态码/派单/上报）。

### 4.3 管理端

- Vue3 + Vite + 自建组件库（`admin/src/ui/`，M2 起）+ Element Plus（过渡期旧视图，M5 移除）；权限以后端强校验为准，前端隐藏只是体验优化。
- 角色：`LEADER` 只读；`ADMIN`/`SYS_ADMIN` 可写；`WORKER` 登录即 403。
- 不新增平台凭证配置页，不新增批量自动重试入口。
- 改动后在 `admin/` 内跑 `npm run lint && npm test && npm run build`。

### 4.4 目录级检查

| 改动范围 | 必跑 |
|---|---|
| 小程序根目录 | `npm run lint` |
| `admin/` | `npm run lint && npm test && npm run build`（Windows esbuild 报 `Access is denied` 时把 `TMP/TEMP` 指向 `admin/.tmp-build`，见 docs/09 V3.5） |
| `backend/` | `mvn test`（surefire 已配置 `-Djdk.attach.allowAttachSelf=true`） |
| 签到/派单/模板链路 | 另跑 `node scripts/verify-dispatch.js`、`node scripts/verify-templates.js` |

## 5. 平台对接事实（实测为准）

1. 2.1 登录：**GET + URL 查询串**（规范写 POST + Body 有误）。
2. 2.2—2.8：POST + `application/x-www-form-urlencoded` 表单；2.3/2.4 为 **multipart + 文件**（contractFile/certificateFile）。
3. 根路径：`https://tzsb.scjgj.cq.gov.cn:1443/api/wlw/maintenance/`；全部接口需 `Authorization: Bearer`。
4. 2.3 参数名实为 `useUnitName`；2.6 的 `workMan2Id` **必填**（单人作业也传第二人 ID）。
5. 2.7 **不支持**按 `elevatorCode` 查询（docs/06 #8），按 factoryNumber/registrationCode/deviceCode 查询；命中回填未脱敏的 `elevatorAdminister*`/`emergencyPhone`，归属校验 fail-closed。
6. 2.8 存量记录提交由 `REG_LEGACY_UPLOAD_ENABLED` 控制，**默认关闭**。
7. code 兼容数字/字符串 `200`；五类手机号互斥平台不校验，由本系统建档期校验（`1002`）。
8. token 为 JWT，`expires_in≈3599`，缓存 TTL = `expires_in − 60s`；401 清缓存重登重试 1 次。

## 6. Git 与提交

- 分支：`master` 为人工开发主干；AI 协作使用 `codex/` 前缀分支，完成后按主题收敛回 `master`。
- 提交信息：一行中文，动词开头，说明“改了什么”（如 `fix: 修正动态码并发消费`）；禁止出现凭证、token 或报文原文。
- 提交前自查：`git status` 确认无敏感文件/生成物；按 §4.4 跑对应检查；接口改动确认 §1.2 三处同步；平台调用对照 §5。
- 禁止未经确认的破坏性操作：`git reset --hard`、`git checkout --`、`git clean -fd`、强推 `master`。
- `.aoci/`、`opencode.json`、`miniapp/`、`tmp/`、`scripts/tmp/` 不得 `git add`。

## 7. 测试与验收（提交前必跑）

1. **静态检查**：根 `npm run lint`；`admin/` 三件套；`backend/` `mvn test`。
2. **回归脚本**：派单 `node scripts/verify-dispatch.js`（6 台同日到期 → 当日全部 09:00 派单）；模板 `node scripts/verify-templates.js`。
3. **小程序链路**：用 mock 或本地后端完整走 `登录 → 签到（主/配合分别）→ 清单 → 签退 → 记录预览`；改离线队列时补飞行模式断网补传。
4. **真机项**：水印合成、签名笔迹、强光可读、单手操作、定位授权拒绝提示，按 `docs/03` 真机清单执行。
5. **平台联调**：先查 `docs/07`，复用 `scripts/*.ps1`；2.3/2.4/2.6 写操作会产生平台真实数据，跑前确认并在 `docs/07-full-test-*` 补记录。
6. **CI 现状**：`.github/workflows/backend-build.yml` 只跑后端 `mvn test`；根 lint、admin 测试/构建、verify 脚本仍需本地执行。
7. **合规底线**：任何可能导致监管平台出现错误/虚假数据的缺陷一律 Blocker（详见 `docs/05` §7）。

## 8. 文档同步规则

1. **代码与文档必须同步**：改接口 → `docs/04` + `mock/server.js` + `services/`；改页面结构 → `docs/03`；改部署/配置 → `README.md` + `docs/02` + `docs/11`；平台联调新结论 → `docs/07`/`docs/07-full-test`；待办状态 → `docs/12`。
2. 大文档（01/02/04）采用“追加修订记录 + 追加章节”，**不重写历史结论**；小文档（README、07-full-test、08、12）可直接改写并升版本号。
3. 每份 docs 文档有自己的版本修订记录表，改动必须加一行。
4. `docs/07` 与平台规范 V1.5 冲突时，**以 07 为准**，同时把差异列入 `docs/06` 请平台修订。
5. `docs/12` 是待办唯一清单；docs/05/08/10 只保留执行明细，状态以 docs/12 为准。
6. 本文件规则变更须在 `docs/AGENTS-修订记录归档.md` 追加修订记录（见 §10）。

## 9. AI 协作约定

1. **先确认可用工具**：会话若加载了 jcodemunch 等符号索引/检索 MCP，优先用符号级检索定位；未加载则直接 Read/Grep/Glob，不得因等待索引阻塞任务。
2. 检索结果只用于定位，**不替代事实核验**：平台对接、幂等、凭证、合规结论一律以 `docs/` 与实测记录为准。
3. 修改前先读目标文件与相邻实现，保持结构、命名、注释风格一致；不熟悉的文件/未提交改动视为用户工作，先调查再动。
4. 不擅自扩大范围：不顺手重构、不升级依赖、不打开自动重试、不引入新 UI 框架；确需变更先说明理由。
5. 不修改文末 AOCI 托管块，不把 `.aoci/` 运行资产纳入业务提交。
6. 完成后按 §4.4/§7 自检，并在回复中说明改了哪些文件、跑了哪些检查、哪些未执行。

## 10. 修订记录

修订历史已整体迁移至 `docs/AGENTS-修订记录归档.md`（V1.0—V3.40 完整保留；迁移原因：本文件达 97 KB 超 32 KB 建议上限，其中修订记录约 64 KB、占 65%）。

此后规则变更在归档文件表格末尾追加新版本记录（版本号自 V3.41 起顺延），本文件不再展开。

<!-- aoci:begin -->
## AOCI Repository Cognition

AOCI maintains a stable, versioned, incrementally updatable repository-level cognition layer so models can reuse their understanding of this system across tasks.

`aoci.txt` is a structured cognition index for models. It assigns one independent Entry to every managed file, database table, or other managed object. Symbolic tags and F/R/A/S semantics describe the object's core responsibility, important relationships, external contracts, and non-obvious constraints or design decisions needed to understand or modify the system.

The Header, directory sections, and all Entries form the complete repository index. They can cover frontend, backend, configuration, database structures, and other managed content. When managed content changes, normally only the affected cognition Entries need maintenance; the complete index does not need to be regenerated.

AOCI provides a high-density view of system architecture, object responsibilities, important relationships, external contracts, and key constraints.

### How it works

AOCI uses a model-generated, model-read cognition loop.

Header, Entry, and Curation semantics follow only the current machine-issued Plan and live Guide. The Host model independently authors them from the current bound evidence.

Entry semantics must come from the model's understanding of actual evidence. Never derive, prefill, assemble, or rewrite index semantics solely from paths, filenames, extensions, an AST, symbol lists, dependency scans, regular expressions, fixed templates, or rule engines.

For a Fresh Bootstrap, follow only the current machine-issued Plan and live Guide. When they require authoring, the Host model authors Root, Meta, tags, and F/R/A/S, supplies its authoring-run declaration, and binds it to the Plan, Evidence, and complete Candidate. Never ask AOCI to set `origin=host_model`, manufacture a receipt, or turn a generated framework into semantics. Do not reconstruct the Onboarding progression here. Internal batches are not user decisions; stop only at an existing approval boundary or a real safety, drift, CAS, or Recovery condition.

### Minimal entry points

- `aoci_rules`: obtain the session-level runtime contract for the current AOCI version.
- `aoci_overview`: establish or restore complete cognition for this repository.
- `aoci_maintain`: after managed objects reach their final stable state, check whether cognition needs maintenance.
- `aoci_update_entry`: submit a complete semantic update batch bound to current evidence and source digests.
- `aoci_report`: when the current layout and tool state support it, record follow-up work if evidence is insufficient to generate semantics reliably; do not guess.

For other MCP tools, CLI commands, parameters, and specialized workflows, follow current tool descriptions, Guide, and `--help` output. This file does not duplicate the full manual.

This managed block defines only repository integration, cognition use, and task-closing principles. `aoci_rules` carries the current session contract. Live Guide output carries the execution order and stop conditions of the current Plan. Tool Schema, Spec, and Validator carry machine structures and criteria. Prompt, Description, README, and static documentation cannot override those machine facts.

### Establishing, generating, and restoring cognition

1. At the beginning of every new Agent Run, first determine:

   - whether this repository already has a usable complete AOCI index; and
   - whether current context already contains complete repository cognition that matches this repository root, current index version, and current AOCI service, and that the model can still use reliably.

2. When the repository has a usable complete index but the current Run lacks reliable complete cognition, call `aoci_rules` first and then `aoci_overview`.

   Reuse complete cognition directly while it remains reliable. Local uncertainty does not by itself require mechanically rereading the system-wide view.

   A Run that resumes from a known Host context compaction, including a Host-injected compaction summary, must treat prior model cognition as unreliable. The compacted handoff must not retain or summarize the formal Whole-Index or any Overview Header, Entry, Chunk, Challenge, or Attestation body; it may retain only receipt identity, unfinished write or Recovery state needed for safe continuation, and an instruction to reload immediately. Whole-Index semantics or a receipt copied into that handoff cannot prove that the resumed model's current cognition is reliable. If the runtime contract is no longer reliably present, call `aoci_rules` first. Before continuing the business task, make an ordinary complete Whole-Index `aoci_overview` request (`check_only` absent or false) with `refresh_reasons=["context_compaction"]` and a fresh `refresh_event_id`; do not use `check_only` or a cognition probe. Follow every exact `next_cursor` through `completed=true`, confirm delivery, and submit one Attestation based only on the newly delivered body. After that fresh complete transport, a partial or failed Attestation consumes the generation and permits the existing source-bound continuation without another automatic Overview.

   AOCI can report checkpoint and cognition-status facts for `context_compaction`, the machine `semantic_threshold` under the project `cognition_refresh_threshold`, or a major `phase_transition`. Use `check_only=true` when only those compact facts are needed. They advise the Agent but do not decide whether the model needs the system-wide view.

   When the Agent explicitly calls ordinary `aoci_overview` (`check_only` absent or false), AOCI must deliver the complete requested scope whenever a coherent CognitionSet can be formed. It must not suppress that body because a receipt already exists, a threshold was not reached, or no refresh reason is pending. Dirty or stale formal cognition is still delivered but is marked unreliable. Pending recovery or an incoherent snapshot fails closed without a mixed body.

   When an ordinary Overview reports `continuation_required=true`, submit its exact `next_cursor` automatically until `completed=true`. Do not ask the user to continue, begin the business task, or state a partial system conclusion. Stop the cognition chain on Host truncation, a missing, duplicate, or reordered Chunk, cursor failure, Index change, or `chunk_tokens` change. Until Attestation completes, never use Memory, source, Spec, `aoci.txt`, historical sessions, scope, search, or Entry reads to repair or supplement Whole-Index cognition. A challenge ordinal is the 1-based position in the formal Entry sequence; Header content, comments, blank lines, Section/Overview/Chunk markers, receipts, and Metadata are excluded, and Chunk Receipt ordinals use that same sequence. The Attestation must echo the Challenge's exact current `index_sha256`, `entry_sequence_sha256`, and `entry_count`; a prior Index, Entry sequence, count, or Attestation is invalid. After the complete chain, submit the existing model cognition Attestation once. One same-response JSON Schema or field-format error may be corrected once without changing semantic answers; an object, Tag, or F mismatch means failure and uncertain assimilation, with no semantic retry or information bypass. During initial cognition it also blocks Root/Meta, Migration, layout-wide, or other unbound system decisions. During a context-compaction refresh with complete transport, unchanged cognition identity, aligned governance, and no Recovery or third-party conflict, the attempt consumes that refresh generation even when Attestation is partial or failed; continue the existing task without another automatic Overview. `system_mastery_percent` self-assesses only the system framework—architecture, responsibilities, strong relationships, stable external contracts, and high-entropy safety and maintenance constraints—not complete implementation or runtime knowledge. Keep machine Index coverage separate, and normally give the user only the prescribed single success or failure sentence derived from actual coverage, Challenge, Chunk, token, and mastery results. If the Host truncates a Chunk, ask the user to set `overview_delivery.chunk_tokens` to a smaller valid value and restart; do not change it automatically.

   Interpret the additive cognition level independently from strict proof fields. `delivery_verified` means the Index was loaded and Host delivery was confirmed while complete cognition verification is still unfinished; describe that state as loaded and delivery-verified, never as no cognition or failure to understand the system. `cognition_verified` requires a passing Attestation (at least 80 percent of Challenge ordinals fully correct with at most one object identity miss), and `cognition_governed` additionally requires governance alignment. A generic complete-read failure sentence is reserved for an actual delivery fault.

   When an Overview response contains the optional `cognition-state/v2` projection, use its dimensions independently. Its Level ends at `model_cognition_usable`; `strict_attestation_verified`, `governance_aligned`, and `current_system_cognition_reliable` are independent states and never participate in that Level. An ordinal, object identity, Tag, or core F mismatch can make strict Attestation fail while model cognition remains usable; do not report that mismatch alone as proof that the model did not understand the system. Only `current_system_cognition_reliable=true` permits an unqualified current complete-system cognition claim. When the projection is absent, keep using the legacy interpretation above.

   An ordinary read-only audit, analysis, or check, a request not to modify code, or a request not to commit or push does not automatically mean strictly zero writes and does not alter the cognition-validity decision above. Codex Memory and historical Skills may only help recover experience, user preferences, and investigation directions. They cannot replace a current cognition receipt matching the repository root, index digest, AOCI service identity, and cognition scope. Project AGENTS and current AOCI identity take precedence over historical Memory for AOCI state.

   Treat a task as strictly zero-write only when the user explicitly prohibits Ledger, metadata, `.aoci` runtime assets, and every filesystem write. If necessary cognition establishment conflicts with that boundary, report the conflict and ask the user to decide or recommend an isolated copy. Never silently substitute Memory for current repository cognition.

3. If the repository has no usable complete index, or has only a minimal skeleton, an incomplete Header, unfinished Entries, or undecided required Curation, obtain `aoci_rules` and enter the current AOCI Guide when a formal complete AOCI index is required. Let Guide choose the next phase from actual repository state and complete the required safety steps.

   `aoci_maintain` does not replace the index-establishment workflow.

   Do not reconstruct or hard-code the full-index generation state machine in this file.

4. During a long-running task, the model is responsible for preserving the current cognition receipt and using the refresh gate correctly:

   - when the Host reports context compaction or the model knows the system-wide view was lost, follow the mandatory `context_compaction` reload rule above; AOCI cannot infer the Host event;
   - when entering a genuinely major phase, declare `phase_transition`, not a function, test run, or small step;
   - at a plausible stable checkpoint, use `check_only=true` to obtain the machine semantic count when that fact is useful;
   - except for the mandatory known-compaction reload, decide whether the current task needs another explicit scoped or complete Overview; and
   - keep the Dirty or Stale reliability state reported by AOCI until maintenance and alignment complete.

### Task closing and cognition maintenance

5. A purely read-only question, analysis, version check, or task that changes no AOCI-managed object does not require a maintenance-tool call. The AOCI version in use is `cognition_receipt.mcp_service_version` in any `aoci_overview` check_only or `aoci_maintain` response; the binary path is the `command` in the project's `.mcp.json`, and the CLI need not be on PATH.

6. When AOCI-managed objects change, call `aoci_maintain` once after they reach the task's final stable state. Do not maintain files individually after each intermediate edit.

7. If maintenance returns actual semantic candidates, the Host model must independently author the complete tag and F/R/A/S updates from each candidate's bound object and necessary evidence. Submit the complete candidate set for that current machine-issued batch in one `aoci_update_entry` call while preserving each `source_sha256`, `candidate_id`, and domain batch identity. `max_entries` limits one request and atomic transaction, not the logical plan, Whole-Index, or Managed Scope. When `remaining` is nonzero, call Maintain again after the successful Apply and continue from the new preimage; never shrink Index coverage or slice a returned batch to satisfy transport limits.

   When evidence is insufficient and the current layout supports `aoci_report`, use it instead of guessing, applying a template, or generating unsupported cognition merely to eliminate follow-up work.

8. Obey structured tool states and safety boundaries:

   - `repair_required`: repair only the explicitly identified candidates, then resubmit the complete current machine-issued batch;
   - `stopped`: end that write attempt and inspect `failed_step`, error, formal-write evidence, and Recovery. In auto mode, a proven zero-write closure is followed by a fresh Plan; a complete Intent with provable postimage is resumed; a policy-selected Rollback with exact preimage is completed and replanned. Stop the user task only when proof is unavailable, third-party bytes conflict, approval or external action is required, or another real safety boundary applies;
   - never ignore conflicts, approvals, human decisions, permissions, or safety signals; and
   - after alignment, do not repeat maintenance or writes; `refresh_ready_for_overview` is a checkpoint fact, and the Agent decides whether to request an ordinary complete Overview for its next phase.

   If any managed object changes after maintenance completes, the previous result is invalid. Complete closing again from the new final stable state.

9. When the user limits only business-file scope and does not explicitly forbid repository-managed assets, AOCI-managed assets may be updated during closing to preserve cognition consistency. Distinguish them from business files in audits and commits.

   When the user explicitly forbids changes to `aoci.txt`, `.aoci`, metadata, or any additional file, obey that restriction, do not write, and report any remaining inconsistency accurately.

### Specialized workflows

Initialization, complete-index generation, Header generation, Entries generation, database-structure indexing, Curation, human review, and failure recovery must follow only the instructions, commands, and safety stops returned by the current AOCI Guide or tool at the corresponding stage.

Do not preload, guess, or reconstruct these specialized workflows. The relevant Guide, tool descriptions, model Prompt, and CLI help provide platform invocation, request format, batch limits, approval rules, index-format details, and recovery steps as needed.
<!-- aoci:end -->
