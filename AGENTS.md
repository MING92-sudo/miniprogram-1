# 项目规则（AGENTS.md）

> 电梯无纸化维保系统——编码与协作规则。AI 助手与人工开发者均须遵守。
> 创建：2026-09-30。V3.0 重写：2026-11-10（规则变更须在 §10 追加修订记录）。
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
| 账号与数据权限 | `docs/14-账号权限与组织角色设计.md` | 三平级部门（安装/维保/使用单位）角色码、数据域与权限矩阵 |
| 测试与上线检查 | `docs/05-测试与验收方案.md`、`docs/11-云托管部署手册.md` | 人工验收结论写回 docs/05、docs/10 |

**当前基线（2026-11-15 机审）**：小程序 50 页（主包 18 + 4 子包 pkg-handover 12/pkg-maintenance 9/pkg-client 4/pkg-admin 7；无原生 tabBar，custom-tabbar 菜单驱动）；`services/` 13 个模块（含 menu/admin）；`mock/server.js` 106 条路由；后端 197 个 Web 映射注解、Flyway V1—V46、477 个单测；管理端 23 个视图（自建组件库，Element Plus 已移除）；文档 01—15。计数用于快速定位，提交前以代码实测为准；文档数字滞后时改文档，不要为迁就旧数字改代码。

## 1. 系统组成与分层

```
├─ app.js / app.json / app.wxss   小程序入口（50 页：主包 18 + 4 子包；无原生 tabBar，custom-tabbar 菜单驱动；页面口径见 docs/03 §3.5）
├─ pages/                         页面（每页 .js/.json/.wxml/.wxss 四件套）
├─ services/                      业务接口层：页面只调 services，禁止直接 wx.request / wx.uploadFile
├─ utils/request.js               唯一请求封装（Bearer、X-Idempotency-Key、401、callContainer/wx.request 分流）
├─ utils/offline.js               离线快照与持久化补传队列（网络恢复自动补传）
├─ config/index.js                环境配置（ENV、useMock、apiBaseUrl、cloud.env/service、LBS key 留空）
├─ constants/index.js             枚举/错误码/业务常量（文案统一从这里取）
├─ mock/                          mock 契约层（server.js 路由 = docs/04 A 部分）
├─ backend/                       Spring Boot 3.3.4 后端（Flyway V1—V45、JWT/BCrypt、平台转发、452 单测）
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
9. **单账号单会话（多角色）**：一个手机号 = 一个账号，同一时刻一个会话令牌（`em_token`）；账号可持多角色（`sys_user_role`），权限取**并集**，数据范围逐角色判断（ScopeResolver 读写谓词，写绝不扩区域/部门）。`activeWorkspace` 决定工作台与 Tab 渲染，可在「我的」切换（重拉 `GET /me/menus?workspace=`）；登录即清旧会话。门户账号经 V41 并入员工体系，`unit_token` 双令牌体系废除（`/unit/login*` 废弃保留契约标注至生产清库）。微信绑定为可选能力，入口只在「我的」页，未开启时登录页不出现微信入口。页面只调 services，跳转走 `utils/nav` 白名单 + `utils/guard` 四层守卫（登录态→角色→权限码→单据状态）。

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
6. 本文件规则变更须在 §10 追加修订记录。

## 9. AI 协作约定

1. **先确认可用工具**：会话若加载了 jcodemunch 等符号索引/检索 MCP，优先用符号级检索定位；未加载则直接 Read/Grep/Glob，不得因等待索引阻塞任务。
2. 检索结果只用于定位，**不替代事实核验**：平台对接、幂等、凭证、合规结论一律以 `docs/` 与实测记录为准。
3. 修改前先读目标文件与相邻实现，保持结构、命名、注释风格一致；不熟悉的文件/未提交改动视为用户工作，先调查再动。
4. 不擅自扩大范围：不顺手重构、不升级依赖、不打开自动重试、不引入新 UI 框架；确需变更先说明理由。
5. 不修改文末 AOCI 托管块，不把 `.aoci/` 运行资产纳入业务提交。
6. 完成后按 §4.4/§7 自检，并在回复中说明改了哪些文件、跑了哪些检查、哪些未执行。

## 10. 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| V1.0 | 2026-09-30 | 首版：项目结构、分层、安全红线、编码约定、平台实测事实、Git/测试/文档同步规则 |
| V1.1 | 2026-09-30 | 派单验收口径改为“同项目 6 台同日到期一次性全部当日 09:00 派单”；补充全量一致性核查与 TDesign 依赖移除后的 lint 要求 |
| V1.2 | 2026-10-02 | 同步实际架构：后端落地状态（97 单测）、admin 目录与目录级 lint/test 命令、docs 范围 01—11；gitignore 补本地 AI 工具缓存与备份文件 |
| V1.3 | 2026-10-03 | 新增 §7 AI 代码导航（jcodemunch MCP）：优先符号级检索、索引自维护、敏感信息约束；原 §7/§8 顺延为 §8/§9 |
| V1.4 | 2026-10-03 | §4.4 更正 2.7 查询口径：平台不支持按 `elevatorCode` 查询（以 docs/06 #8 / docs/04 A.0.2 实测为准），改为 factoryNumber/registrationCode/deviceCode |
| V1.5 | 2026-10-03 | 全量代码审核整改后同步：①§1 结构表口径更新（小程序 20 页、后端 Flyway V1—V11 + 137 单测、补 `scripts/fixtures/` 与根目录 `doc_text.txt` 说明）；②§3 目录级检查补 admin 构建的 `TMP/TEMP` 规避办法与 verify 脚本触发条件；③§7 更正 jcodemunch 可用性前提——MCP 工具并非每个会话都加载，开头先确认、不可用即回退 Read/Grep/Glob；④§2.5 敏感文件口径澄清：合成测试 PDF 移至 `scripts/fixtures/`（`scripts/tmp/` 保持 gitignore 且不跟踪任何文件） |
| V1.6 | 2026-10-03 | 已裁模块端点删除后同步计数（后端 Flyway V1—V12、100 条路由、136 单测）；明确**删除端点不删表**（`location_appeal`/`alert_rule`/`alert_record`/`op_log` 等保留以满足留存），恢复交付仍按 §1.2 三处同步 |
| V1.7 | 2026-10-05 | 计数更正：后端 Flyway V1—V20（20 个迁移）、138 单测；M15 文档漂移整改 |
| V1.8 | 2026-10-05 | §2.3 红线表述同步：删除零调用的 `retry-auto`/`REG_RETRY_AUTO` 配置（Minor6），红线改为"无自动重试实现，确认前禁止引入" |
| V1.9 | 2026-11-03 | 安全/合规 P0 移植后计数更新（后端 169 单测）；明确派单仅定时任务驱动、异常项 S0—S7 显式判定、2.7 归属 fail-closed、JWT 无默认密钥 |
| V2.0 | 2026-11-03 | 安全/合规 P0 移植收尾（自 `codex/audit-fixes` 按主题合入，codex 分支已全部收敛为 `master`）：①微信登录改 `wx.login` code → 后端 `jscode2session` 换 openid（新增 `WX_APPID`/`WX_APPSECRET`，§2 红线口径不变：凭证只进环境变量/`.env`）；②`/auth/bind-*` 改为登录后写操作，同 openid 先解绑再绑定（V21 迁移 + `uk_openid`）；③`GET /files/{id}` 增加 URL 时效签名 `?s=`（`FileAccessTokenService`）；④§1 计数更新（Flyway V1—V21、184 单测），docs/02 V1.8、docs/04 V2.30 同步 |
| V2.1 | 2026-11-05 | 整理轮：①§0 导航新增 `docs/12-未完成事项清单.md` 为**待办唯一清单**（docs/08 降为阶段与历史口径）；②§1 结构表 docs 范围改 01—12；③约定根 `npm run lint` 只覆盖小程序代码（新增 `.eslintignore` 排除 admin/backend/docs，未装 admin 依赖也可通过），admin/backend 仍在各自目录内校验 |
| V2.2 | 2026-11-06 | 设备信息（用户需求）：小程序新增「设备信息」入口（`pages/elevator/list` + `pages/elevator/detail`，复用既有 `GET /elevators`、`GET /elevators/{id}/profile`），电梯列表/档案接入班组数据权限（V7 同谓词，仅本人负责的电梯）；§1 计数更新（小程序 22 页、后端 185 单测），docs/03 V2.5、docs/04 V2.31 同步 |
| V2.3 | 2026-11-06 | 项目名称/小区名称字段独立（用户需求）：`elevator` 新增 `project_name` 列（V22 迁移），与使用单位名称（物业公司）区分——历史实现曾把「项目名称」误回显为使用单位名称；新增 `PUT /elevators/{id}/project-name`（维保人员现场更正，班组数据权限同 V2.2 谓词，仅本人负责的电梯，越权 1403/空值 422）；电梯列表/档案/工单视图的 `projectName` 改取 `elevator.project_name`；管理端电梯页/台账补「项目名称（小区）」列与表单项。§1 计数更新（Flyway V1—V22、后端 187 单测），docs/01 V3.5、docs/03 V2.6、docs/04 V2.32、docs/09 V3.14 同步 |
| V2.4 | 2026-11-06 | 品种取值统一（用户需求）：电梯品种（category）取值统一为 TSG T5002 第八条四类口径——曳引与强制驱动电梯/液压驱动电梯/杂物电梯/自动扶梯与自动人行道 → 附件 A/B/C/D；backend/mock 的 `CATEGORY_APPENDIX` 由细分七类改为四类，与管理端建档表单、`DemoDataSeeder`、`ChecklistTemplateSeeder.APPENDIX_SCOPE` 对齐，消除液压/扶梯/人行道电梯检查项静默回退附件 A 的缺陷；新增 `ChecklistServiceTest.categoryRoutesToCorrectAppendix`。§1 计数更新（后端 188 单测），docs/01 V3.6、docs/03 V2.7、docs/04 V2.33 同步 |
| V3.0 | 2026-11-10 | **重写**：按“导航→架构→红线→业务不变量→编码→平台事实→Git→测试→文档→AI 协作”重组，合并重复条款；更新基线为 22 页 / 47 条 mock / 101 条业务路由 / 22 个迁移 / 196 单测；新增 §3 关键业务不变量（工单归属、签到、动态码原子消费、签退校验、上报快照、派单基线、文件写穿缓存、工单号重试）；安全红线补充 WX/JWT/COS/文件签名与 CI 占位密钥口径；AI 导航改为工具无关并注明 AOCI 托管块不可手改 |
| V3.1 | 2026-11-10 | 后端启动新增**迁移失败自愈**：`migrate()` 前按主键删除 `flyway_schema_history` 中 `success=0` 的失败行后重跑该迁移，只删失败行、不重写已成功迁移的 checksum（`FLYWAY_REPAIR` 的 M6 语义不变）；背景是云托管 MySQL 5.7 上 V22 迁移中断导致容器崩溃重启循环、且环境拿不到数据库写权限。补 `FlywayRepairConfigTest` 4 例（后端 200 单测），docs/11 新增 §8「迁移中断自愈与排查」 |
| V3.2 | 2026-11-10 | 打包完整性护栏：新增 `MigrationIntegrityTest`，classpath 下出现同版本迁移脚本即失败（云端 133 版本曾因 `backend/target/classes` 残留 2026-10-01 的 `V1__init.sql` 被打进 jar，Flyway 报 "Found more than one migration with version 1" 拒绝启动）；后端打包必须 `mvn clean package`，基线 201 单测 |
| V3.3 | 2026-11-11 | 双人维保协同：签字各自手机签（用户需求）——①§3 业务不变量新增「签字严格各签各的 + 签退仅主维保」：`work_order` 增 `worker_signature_file_id/worker_signature_at/assistant_signature_file_id/assistant_signature_at` 四列（V23 迁移），新增 `POST /work-orders/{id}/signature`（普通作业人员仅可签本人角色，组长/管理员可代签，越权/代签 `1403`），签退改为仅主维保可操作、签字取工单字段（兼容旧客户端签退体直传）；②前端签字入口移到工单详情页，签退页由「两个签字位」改「签字状态展示」；③计数更新（Flyway V1—V23、102 条业务路由、216 单测、mock 48 条）。docs/01 V3.8、docs/03 V2.10、docs/04 V2.41 同步 |
| V3.4 | 2026-11-12 | 导出表单标准化：新增 `docs/13-维保与急修记录表单设计.md`（§0 导航新增一行、docs 范围 01—13），《电梯维护保养记录》与《电梯急修记录单》按 TSG T5002—2017 第七条/第八条/第十条/附件 A—D 定为标准表格版式（六列项目表、品种×附件表号副标题、分页重复表头、页脚页码、签字确认区）；`RecordPdfService` 全量重排，`DirectoryService.enrichFaultViews` 补急修单设备字段与 `dispatchedAt`（docs/04 V2.45，只增不改）；新增采集缺口 C-08—C-10 入 docs/12。计数更新（Flyway V1—V27、231 单测） |
| V3.5 | 2026-11-12 | 账号权限体系设计：新增 `docs/14-账号权限与组织角色设计.md`（docs 范围 01—14）——系统权限下设**安装部/维保部/使用单位三个平级部门**，9 个组织角色 + 2 个平台角色，部门域互斥、部门内五类数据谓词、管理端与小程序两张权限矩阵、10 条硬规则、四层鉴权链（Auth→RoleCatalog→PermissionMatrix→DeptScope）、V28 迁移（`sys_employee` 增 `department/manager_id/job_title`）、现有 5 角色迁移路径与 12 条越权验收用例。本期仅设计，P1—P4 落地排期见该文 §11 |
| V3.6 | 2026-11-12 | 三工作流并行落地：①**权限底座 P1**——`RoleCatalog`/`PermissionMatrix`/`RequirePerm`/`DeptScopeService` + V31 迁移（`sys_employee` 三列），`AdminRoleInterceptor` 改走能力码，现有 5 角色放行结果逐条不变（`AdminRoleInterceptorCompatTest`）；②**管理端权限前端**（P2 半边）——`admin/src/utils/permission.js` 与后端逐字同名 24 个能力码、14 条路由 `meta.perm`、菜单数据驱动过滤；③**外接建档 W2（Task 7）**——V29 五表 + 5 端点，合同前置/缺件待补办/接管查验责任归属必填/归档 `archive_source=2` + 首保 +3 日；④**维保日历（Task 11）**——`GET /maintenance-calendar` 只读投影；⑤**区域可见范围（Task 8）**——新增 `visibleReadPlatformIds`（区域∪本班组∪本人，仅列表），写谓词保持严格（防代签到）。计数更新（Flyway V1—V31、284 单测、admin 39 用例）；⑥**管理端菜单下发**（docs/14 P2 收尾）——`GET /auth/menus` + `MenuService` 按角色能力过滤菜单树，前端接口驱动 + 本地兜底 |
| V3.8 | 2026-11-13 | 移交/外接建档/门户全链路落地与上线：①Task 9—14 完成（使用单位账号 V34、门户接口、小程序 17 页、管理端 4 视图）；②云托管发布失败定位为 **Flyway 校验（V30 缺口）** 而非探针，修复方式：V30 重编为 V34，根因与排查顺序写入 docs/11 §0；③镜像改分层 jar（依赖层/业务层分离）；④新增使用单位微信免密登录 `POST /unit/login-by-wechat` 与门户入口。计数更新（40 页、73 mock 路由、V1—V34、322 单测、21 视图） |
| V3.7 | 2026-11-12 | 安装移交维保检查清单定型：新增 `docs/15-安装移交维保检查清单表单设计.md`（§0 导航新增一行、docs 范围 01—15）——按 GB/T 10060—2023 表 1 定 74 项 / 13 项类 / ☆49 重要项的标准检查表，不合格项按 `item_no` 1:1 自动生成整改清单（不可增删、逐条复验），判定照 7.2/7.3 分 5 条路径，配 20 项资料核对 D1—D20（含既有 3 项强制卡点）、NA 原因码、5 节点签字审批、逐项照片与一梯一档归档目录（COS + `?s=` 重签 + SHA-256 + 4 年留存）；列表单落地提案（V32 迁移 + 9 端点，本期仅设计）与现状 7 项差异（**任一不合格即整改**、**逐条复验**两处行为变更待实施）。§3 业务不变量与红线不变 |
| V3.9 | 2026-11-13 | 单身份登录（用户需求：两个账号不能同时在线）：①员工态（em_token）与门户态（unit_token）互斥——services/unit.saveUnitSession 内清员工态、app.setAuth 与登录页员工分支清门户态、冷启动 restoreAuth 若两套并存保留员工态；②「我的」页按单一身份渲染（门户态 → 门户卡片 + 微信绑定；员工态 → 个人信息与按角色下发入口；无身份 → 登录引导），不再同时显示两张卡片；③退出门户即退出本次登录并回登录页；门户首页补「我的」入口（switchTab）；④登录页收敛为唯一账号密码表单（删除页面底部使用单位入口、去掉 11 位手机号限制、取消登录后自动绑定微信），微信绑定与免密登录改为「我的」页可选能力；⑤新增 §3.9 业务不变量，docs/03 V2.12 同步 |
| V3.10 | 2026-11-13 | 使用单位「我的」独立成页（用户需求：门户账号只看单位信息与设置，不渲染维保内容）：①新增 `pages/unit/mine`（小程序 40 页）——单位信息（单位名称/登录账号/角色）+ 绑定微信一键登录 + 修改口令 + 退出登录，不含任何作业入口；②`pages/mine` 只服务员工态，检测到门户身份即回门户首页；门户首页「我的」改 `navigateTo` 该页（非 tab，无维保 tabBar）；③后端新增 `POST /unit/account/password`（门户令牌，原口令校验 + 新口令 ≥6 位且不同于原口令，只更新 BCrypt 哈希），`UnitAccountServiceTest` 补 4 例；会话返回体补 `account`；④mock 与 docs/04（V2.50）三处同步，迁移与种子数据不变 |
| V3.11 | 2026-11-14 | M1 后端权限底座（管理端重构「梯维通」蓝图，策略 A 底座先行分批；spec/plan 见 docs/superpowers）：①V35 权限核心五表（sys_org/sys_user_role/sys_role_scope/sys_grant/sys_audit_log）+ sys_employee.scope_version + 13 角色默认范围种子；V36 dev/test 由存量旧角色回填（生产空库 no-op，张工归 Seeder，严禁自动 TRUNCATE/DELETE）；②RoleCatalog v2 十三规范角色 + 旧码别名 + 同部门兼任（R7）；PermissionMatrix v2 八十六码三态（Y/C/DENY）唯一事实源，24 旧常量/GRANTS 冻结保留；LegacyCapabilityTranslator 24→71 桥接（adminConsole 门控 + order/fault 语义反转，allows 逐格复现冻结授权，RbacMatrixTest/CompatTest 锁定）；③JWT 增 scopeVersion claim（保留 5 参重载）+ AuthInterceptor v2 多角色集 ATTR_ROLES + sv 校验（旧 token null→0 兼容）；ScopeResolver 读写双谓词与 V7 逐集合等价（写绝不扩区域/部门）；④GrantService 限时授权四态 + 到期回收 bumpScopeVersion + 审计；AuditLogService append-only + 越权 403/1403 BLOCKED 留痕（op_log 并存）；⑤DemoDataSeeder 增张工 INST_MGR（INSTALL）与组织角色锚点。旧端点行为逐条不变，业务查询/admin/小程序零改动；M1 不新增 REST 端点（grant 管理 M3、audit 视图 M5），docs/04 三处同步不触发。计数更新（Flyway V1—V36、362 单测）；门禁全绿（后端 mvn clean test/package、verify-dispatch/templates、admin lint/39 用例/build）。M2—M5 与生产清库见 docs/12 G 组 |
| V3.12 | 2026-11-14 | M2 自建组件库 + 应用壳（管理端重构「梯维通」，纯前端；spec/plan 见 docs/superpowers）：①`admin/src/ui/` 深色工业风 token 层（admin-spec §8 逐字）+ inline SVG 图标 + 状态色映射；②9 组件（DataTable/Drawer/Modal/Form/Toast/StatusTag/Timeline/PermButton/AppShell）+ 四态 + 4 composables（useTable/usePagination/useForm/usePermission），styled SFC + headless 分离；③permission-v2 前端镜像 M1（13 角色/86 码三态 + canonical 别名 + 漂移守卫）；④双布局共存：新 AppShell 包 login/工作台/403/404，旧 17 视图保留 AdminLayout + EP（/legacy 前缀保活），EP 到 M5 移除；⑤工作台 M2 用 admin-spec §9 mock 基准集 + 真实多角色切换机制（roleSet 接线 M3）；⑥开发态组件廊 + 九不变量自检；⑦移除无引用 exceljs。旧 39 用例零回归 + 新增 66 用例（共 105）全绿，lint/build 通过，浏览器走查登录/组件廊/自检渲染无报错。M2 不新增 REST 端点，docs/04 三处同步不触发。docs/09 V3.17、docs/12 V1.7 同步 |
| V3.13 | 2026-11-14 | M3—M5 视图分批落地与旧管理端下线（管理端重构「梯维通」收尾）：①M3 台账域——V37（sys_invite/biz_project/biz_elevator_change）+ MultiRoleGate 多角色三态门控 + 组织/项目/邀请/限时授权/电梯双人变更/账号/角色矩阵端点 + 7 视图；合同金额服务端分级脱敏；②M4 作业域——V38（记录版本链+solo_exempt、故障代报修+SLA、工单SLA）+ 记录修订 v2+新哈希（不重报，P-5）/代报修端点 + 移交/计划/记录/调度/工单详情 5 视图 + 合规组并入新壳；③M5 数据域——V39（sys_config/biz_export_approval）+ 配置/敏感导出审批/统计/审计只读端点 + 3 视图；移除 Element Plus 与旧 AdminLayout/旧 permission/menu/旧 EP 视图，guard/auth 切 permission-v2；小程序 role-view 对齐 13 规范角色（兼容旧码、单身份互斥不变）。计数更新（Flyway V1—V39、375 单测、173 映射、23 视图）；门禁全绿（后端 mvn test、admin lint/75 用例/build 无 EP 包、verify-dispatch/templates）。生产清库仍为人工上线步骤（docs/12 G 组） |
| V3.14 | 2026-11-14 | C-12 安装移交维保检查清单后端核心落地（GB/T 10060—2023 表1，docs/15）：①V40 迁移扩 `handover_order`/`handover_check`/`handover_check_item`/`handover_rectification`/`handover_confirmation` + 新增 `handover_doc`/`handover_photo`/`handover_archive`（SHA-256）；②`HandoverChecklistTemplate` 74 项（13 项类/☆49 重要项/试验项 57—74）、`HandoverChecklistService` 7.2/7.3 五路径判定（重要项任一不合格即 DISQUALIFIED、一般项 >8 DISQUALIFIED、1—3 且三条件齐备 CONDITIONAL、其余 RECTIFY、全合格 QUALIFIED）+ 不合格项按 `item_no` 1:1 生成整改（不可增删）+ 逐条复验（全 PASS 才回待确认）+ ☆项 NA 经理审批（`handover_sign` 节点 NA_APPROVE）+ 一梯一档 7 条目录 SHA-256（幂等）；③`HandoverPdfService` 三张表单导出（CHECK/RECT/INDEX，A4 多页重复表头 + 页脚「内部移交把关，不替代监督检验」）；④8 端点经 `MultiRoleGate`（HANDOVER_VIEW/INSPECT/RECTIFY/REVIEW/EXPORT）门控；⑤`HandoverService.submitInspect` 改任一查验项 FAIL 即强制整改（D-2，删除 RECTIFY_ITEM_TYPES 两类限定），`recheckRectify` 增 rectId 逐条复验分支（保留整批兜底）。§3 业务不变量「清单与签退」口径不变（移交清单独立于维保签退）。新增 `HandoverChecklistServiceTest` 20 例；mock/services/docs/04（V2.51）三处同步。计数更新（Flyway V1—V40、395 单测、181 映射、mock 82 路由）；后端 mvn test 全绿、根 lint 通过（worktree 点路径致 `eslint .` 全忽略，改用显式文件 --no-ignore 校验）。剩余前端逐项留证 UI 与管理端清单视图见 docs/12 C-12（部分关闭） |
| V3.15 | 2026-11-14 | C-12 管理端落地（前后端，小程序不动）：①`HandoversView` 由只读列表重建为主从视图——列表 + 状态筛选 + 行点击抽屉，抽屉三 Tab（74 项检查清单按 G01—G13 分组逐项留证：结果/实测值/不合格描述/NA 原因码 NA-01—05/照片上传，☆与试验项不合格强制照片；1:1 整改清单逐条上传整改 + 逐条复验；一梯一档 7 条目录 SHA-256）；判定结论由后端冻结回显（前端不改写），有条件移交 7.3 三条件输入；②`PermButton` 三态门控 handover:inspect/rectify/review/export，NA 审批/复验/整改走 `Modal`，三张表单 PDF blob 导出，照片复用 `api/upload`→`/files/upload`；③`DataTable` 增可选 `clickable`+`row-click`（向后兼容，旧视图零回归）；④`api/handover.js` +8 方法；⑤后端 `HandoverService.init` 接收表头三字段（installCompleteDate/installUnitName/specialType）+ 新增 `GET /handovers/{id}/archive`（按移交单，幂等）。门禁：admin lint/90 用例（+9）/build 无 EP 包、浏览器壳走查无报错（登录壳渲染正常；移交全流程 E2E 因本地无 MySQL 未跑，由 7 个视图交互测试覆盖）；后端 mvn clean test 396 全绿、根 lint 绿。mock/docs/04（V2.52）/docs/09（V3.20）/docs/12（V1.11）同步；小程序 services/pages 本期不动（C-12 查验页逐项留证 UI 暂缓）。计数更新（182 映射、mock 83 路由、396 单测） |
| V3.16 | 2026-11-15 | 删旧管理端 + 前后端端到端对齐 + 云托管部署冒烟（管理端「博威无纸化」）：①删 `menu-config.js` LEGACY_GROUP 死链（旧视图 M5 已删）；②`PermButton` 缺省 role 回退登录角色，修复各页操作按钮误禁用；③电梯台账补档案增删改查 + 行点击「一梯一档」抽屉（profile + archive 只读 SHA-256），后端 `archiveByElevator` 按电梯取最近移交归档；④上报异常清单补逐条人工重报 + PDF 导出，上报日志改用 `/reg/upload-logs`；⑤修正分页解包（记录/移交/工单/建档/模板/上报异常取 `.list`、日历取 `.entries`），confirmStatus 中文化；⑥人员查询失败显式提示。云托管 springboot-l1ws-168 上线（FlowRatio 100），26 端点冒烟 25 绿；`/platform/workers` code=2001 为监管平台 token 获取失败（platformConfigured=true，平台 2.1 登录从云容器失败，属凭证白名单/网络外部依赖，非代码缺陷）。admin 96 用例/lint/build 绿、后端 396 单测绿；docs/09 V3.22、docs/12 V1.12 同步；小程序不动。计数不变（182 映射、mock 83、396 单测） |
| V3.17 | 2026-11-15 | **更正 V3.16 监管平台结论 + token 登录重试**：复核证实监管平台链路正常（平台代码自 192c9c6 未改、平台 2.1 端点可达、`/platform/workers` 返回真实人员数据），V3.16 所记「2001 外部依赖损坏」结论有误——实为**部署冷启动瞬时 token 获取失败**（RestTemplate 连接超时 3s 偏紧，首登超时即 2001，token 缓存后恢复）。修复：`PlatformTokenService.login` 对**网络瞬时异常重试 1 次**（平台业务非 200 不重试；2.1 为幂等 GET 登录，与 §2.4 上报禁止自动重试无关），新增 `PlatformTokenServiceTest` 3 例（瞬时重试成功/持续异常 2001/业务失败不重试）。云托管 springboot-l1ws-169 上线（FlowRatio 100），冷启动首调即成功，26 端点全量冒烟 **26/26 绿**。后端 399 单测全绿；docs/09 V3.23、docs/12 V1.13 同步 |
| V3.18 | 2026-11-15 | **小程序重构 M2—M6 落地（博威无纸化原型 V1.3）**：①§3.9 重写为单账号单会话多角色（V41 账号统一，unit_token 双令牌废除，权限并集/数据范围逐角色，activeWorkspace 切换工作台）；②小程序 49 页（主包 17 + 4 子包），删原生 tabBar 改 custom-tabbar 菜单驱动（/me/menus 下发 + config/menus 镜像），全局 custom 导航 + nav-bar；③深色工业风设计系统（.bw 作用域）+ 14 共享组件 + 四层守卫（config/routes 白名单 + utils/guard + utils/nav）；④合规主干逐字保留（签到 GPS/动态码/清单/30 分钟/各签各的/签退快照+2.6 即报，脚本化迁移 diff 核验）；⑤记录生命周期 UI（V42）+ 急修七态（V43）+ C-12 74 项逐项留证 + 使用单位签署门控/评价；⑥删全部旧页 + role-view.js；⑦基线计数更新（49 页/13 services/Flyway V1—V43/452 单测）。门禁：verify-miniapp-routes 10/10、verify-miniapp-m0 12/12、m1-sync 12/12、dispatch/templates/lint 绿。剩余：移交内部审核/作废后端端点（docs/12 C-13）、真实绘制走查待开启开发者工具服务端口、master 整合与生产清库人工门禁 |
| V3.19 | 2026-11-15 | **忘记密码自助重置（用户需求）**：①登录页「忘记密码」改 fs20 与「记住账号」同字号、靠右对齐，点击跳新增 `pages/login/forgot`（主包 18/总 50 页）；②新增 `POST /auth/reset-password-by-wechat`（免登录白名单；code 服务端换 openid 匹配绑定账号，未绑定 422 引导管理员重置、停用 403、新密码 ≥6 位；重置后 `EmployeeMapper.clearSessions` 清两端会话旧 token 立即 401，回传 account 供登录页预填）；管理端重置仍走 `POST /employees/{id}/reset-password`；③services/auth.resetPasswordByWechat + mock + docs/04 V2.56 三处同步，docs/03 V2.19、M0 映射/spec §1.4 与 m0 守卫基线 49→50；`ResetPasswordByWechatTest` 5 例。门禁：后端 467 单测全绿、根 lint、routes 10/10、m0 12/12。计数更新（50 页/106 mock/197 映射/Flyway V1—V46/467 单测） |
| V3.20 | 2026-11-15 | **微信绑定接线验证（用户需求）**：①客户端闭环 mock 实测全绿（登录→我的→绑定→退出→登录页「微信一键登录」→工作台，截图取证）；②补 `WechatBindLoginTest` 5 例（绑定先解绑他人再同事务落库/未登录 401/免密登录签发会话/未绑定 422/停用 403），472 单测全绿；③mock `/auth/wx-login` 补齐 M1 扩展响应（loginPayload 与 /auth/login 共用）；④真实 API 探针：模拟器 wx.login 取真实 code 成功，云容器 jscode2session 外联失败（真实/无效 code 对照均 2001 通用异常分支）→ docs/12 C-14 待人工排查（云托管日志/公网出口/WX_APPID 与 appid 一致性）；MCP 绑定 cloud1 读不到 prod 日志。services 过时注释修正 |
| V3.21 | 2026-11-15 | **C-14 根因修复（用户裁决：信任出口网关证书）**：MCP 重绑 prod + tcb CLI 安装后开通 CLS 取容器日志，定位云出口网关（169.254.10.1）对 443 TLS 拦截、呈现轮换自签证书致 JVM PKIX 拒绝（监管平台 1443 不受影响）。`WxAuthService.GatewayAwareTrustManager` TOFU：默认严格 PKIX，仅网关画像（自签+CN=api.weixin.qq.com）校验失败时信任+指纹审计；`wxRestTemplate` 专用 Bean、platformRestTemplate @Primary；`GatewayAwareTrustManagerTest` 5 例，477 单测全绿。真实环境实测：wx-login code=0 签发会话、bind-wechat `{code:0,ok:true}`。docs/12 C-14 关闭。部署口径：tcb CLI deploy 走 Git 镜像有滞后，MCP manageCloudRun deploy 本地上传为准 |

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
