# 项目规则（AGENTS.md）

> 电梯无纸化维保小程序——编码与协作规则。AI 助手（Codex 等）与人工开发者均须遵守。
> 创建：2026-09-30。修改规则时同步更新版本记录。

## 0. 快速导航

| 需要了解 | 查阅 |
|---|---|
| 需求与合规依据 | `docs/01-需求文档.md`（TSG T5002 + 平台规范 V1.5 逐字核对） |
| 平台接口怎么调 | `docs/07-平台联调实测记录.md` + `docs/07-full-test-20260930.md`（**实测优先于规范**） |
| 内部 API 契约 | `docs/04-接口文档.md` A 部分（= `mock/server.js` 路由） |
| 当前进展 | `docs/08-项目审查与开发计划.md` |
| 管理端设计与验收 | `docs/09-管理端设计.md` + `docs/10-管理端验收清单.md` |

## 1. 项目结构与分层

```
├─ app.js / app.json / app.wxss   小程序入口（20 页，tabBar 4 个；范围收敛后口径见 docs/03 §3.2）
├─ pages/                        页面（每页 .js/.json/.wxml/.wxss 四件套）
├─ services/                     业务接口层：页面只调 services，禁止直接 wx.request
├─ utils/request.js              唯一请求封装（Bearer 鉴权、X-Idempotency-Key、401 处理）
├─ utils/offline.js              离线持久化队列（网络恢复自动补传）
├─ config/index.js               环境配置（useMock 开关、apiBaseUrl、LBS key 留空）
├─ constants/index.js            枚举/错误码/业务常量（文案统一从这里取）
├─ mock/                         mock 契约层（server.js 路由 = docs/04 A 部分）
├─ backend/                      Spring Boot 3.3.4 后端（Flyway V1—V12 + JWT/BCrypt + 平台转发 + 136 单测）
├─ admin/                        Vue3 + Vite + Element Plus 管理端（lint/vitest/build）
├─ scripts/                      平台联调 PowerShell/Node 脚本（fixtures/ 存合成测试件；tmp/ 已 gitignore）
├─ doc_text.txt                  平台《电梯维保记录上报接口规范 V1.5》原文提取（docs/04 B 部分的核对来源，只读）
└─ docs/                         01—11 编号文档（见 §8 文档规则）
```

**分层规则**：
1. 页面 → `services/*` → `utils/request`，禁止页面直接发起网络请求或直接读 storage 里的业务数据；
2. 新增接口时**三处同步**：`services/` 方法、`mock/server.js` 路由、`docs/04` A 部分条目；
3. `services/` 层方法签名不得因 mock↔真实切换而变化（`useMock` 只在 `request.js` 分流）；
4. 后端改动须与 `docs/04` B 部分实测结论一致，不得按 V1.5 规范原文实现已被实测推翻的调用方式（如 2.1 用 POST）。

## 2. 安全红线（最高优先级）

1. **平台凭证 `REG_*`（username/key/appcode/secret）严禁**写入代码、mock、文档、注释、`container.config.json` 或任何入库文件；只允许存在于 `.env`（本地，已 gitignore）与云托管环境变量。`.env.example` 只留变量名与格式说明。
2. **LBS key 同理不入库**（`config/index.js` 中 `lbs.*Key` 留空）。
3. `originalRecordId` 幂等性未经平台书面确认前，**任何自动重试逻辑默认关闭**（`REG_RETRY_AUTO=false`、后端 `retry-auto=false`）；禁止在提交/PR 中"顺手"打开重试。
4. 上报相关日志必须脱敏：不打印完整 token、手机号、密钥。
5. `.env`、证书 PDF、合同 PDF 等真实数据文件不得提交；`scripts/tmp/`、trace 文件提交前确认已 gitignore。

## 3. 编码约定

- **语言/风格**：小程序 CommonJS（`require`/`module.exports`），ES2018，2 空格缩进，单引号，分号按现有文件风格；提交前 `npm run lint`（eslint）须通过。
- **命名**：services 方法动词开头（`getXxx/createXxx/submitXxx`）；常量 UPPER_SNAKE；页面 data 字段与 `docs/04` 字段名一致。
- **注释**：每个文件顶部一行职责说明；涉及平台/合规的约定注明出处（如 `docs/01 §3.17`、平台 2.6）。
- **错误码**：统一用 `constants/index.js` 的 `ERROR_CODES`，禁止页面内硬编码文案。
- **枚举**：维保类别 FM/HM/TM/SM/OY、隐患码 S0—S7、判定方式 NUMERIC/STANDARD/MANUFACTURER/QUALITATIVE 只从 `constants` 引用；隐患码按 S0—S7 连续编码（规范原文 S3 重复为笔误，待平台确认 docs/06 #9）。
- **时间**：存取一律 `yyyy-MM-dd HH:mm:ss`（GMT+8），用 `utils/util.js` 的 formatTime/parseTime（iOS 兼容已处理）；时长 `HH:mm:ss`。
- **幂等**：所有写接口自动携带 `X-Idempotency-Key`（`request.js` 已实现），新写接口不得绕过。
- **微信小程序规范**：新页面四件套齐全并注册进 `app.json`；`requiredPrivateInfos` 变更须同步说明用途；不得引入新 UI 框架（TDesign 已移除，如重新引入须按 docs/08 重新评估并同步文档）。
- **后端**：Java 17 / Spring Boot 3.3.4；统一 `ApiResponse` 返回；配置经 `PlatformProperties`（前缀 `platform.`）从环境变量注入；容器端口 80（`SERVER_PORT`）。
- **目录级检查**：管理端改动在 `admin/` 内跑 `npm run lint && npm test && npm run build`（Windows 下若 esbuild 报 `Access is denied`，把 `TMP/TEMP` 指向工作区内目录如 `admin/.tmp-build` 再构建，见 docs/09 V3.5）；后端改动在 `backend/` 内跑 `mvn test`；改到签到/派单/模板链路时另跑 `node scripts/verify-dispatch.js` 与 `node scripts/verify-templates.js`。

## 4. 平台对接事实（实测为准，勿按规范臆测）

1. 2.1 登录：**GET + URL 查询串**（规范写 POST+Body 有误）。
2. 2.2—2.8：POST + `application/x-www-form-urlencoded` 表单；2.3/2.4 为 **multipart + 文件**（contractFile/certificateFile）。
3. 根路径：`https://tzsb.scjgj.cq.gov.cn:1443/api/wlw/maintenance/`；全部接口需 `Authorization: Bearer`。
4. 2.3 参数名实为 `useUnitName`；2.6 的 `workMan2Id` **必填**（单人作业也传第二人 ID）；2.7 **不支持**按 `elevatorCode` 查询（docs/06 #8 结论已修正），按 factoryNumber/registrationCode/deviceCode 查询，命中回填未脱敏的 `elevatorAdminister*`/`emergencyPhone`。
5. code 兼容数字/字符串 `200`；五类手机号互斥平台不校验，由本系统建档期校验（`1002`）。
6. token 为 JWT，`expires_in≈3599`，缓存 TTL = expires_in − 60s；401 清缓存重登重试 1 次。

## 5. Git 与提交

- 分支：`codex/` 前缀（AI 协作）；人工开发沿用现有 `master` 流。
- 提交信息：一行中文，动词开头，说明"改了什么"（如 `feat: 接入离线队列照片补传`）；禁止在提交信息中出现凭证或报文原文。
- 提交前自查：`npm run lint`；涉及 `mock/`、`services/`、`docs/04` 三处同步；涉及平台调用对照 §4。
- 敏感文件（`.env`、`*.pdf` 真实合同/证书、trace 日志）不得出现在 `git add` 中。

## 6. 测试与验证

- 前端改动：微信开发者工具跑通受影响页面；涉及工单/签到/上报链路的改动，用 mock 数据完整走一遍 签到→清单→签退。
- 派单逻辑改动：跑 `node scripts/verify-dispatch.js`（6 台同日到期 → 当日一次性全部 09:00 派单）。
- 平台联调改动：先查 `docs/07`，复用 `scripts/*.ps1`；写操作（2.3/2.4/2.6）会产生平台真实数据，跑前确认并在 `docs/07-full-test` 补记录。
- 合规底线：任何可能导致监管平台出现错误/虚假数据的缺陷一律 Blocker（详见 `docs/05` §7）。

## 7. AI 代码导航（jcodemunch MCP）

> 本仓库已由 jcodemunch 建立符号级索引（292 文件 / 2590 符号）；watcher 已安装（登录自启），代码变更自动增量重索引。
>
> ⚠️ **可用性前提（V1.5 更正）**：其 MCP 工具（`route`/`menu`/`order`/`search_symbols` 等）并非每个会话都会加载——**会话开头先确认工具是否存在**（或 `list_repos` 是否可调用）。若不可用，直接回退 Read/Grep/Glob 完成导航，不得因等待索引而阻塞任务；本节其余约束（敏感信息、事实核验）不受影响。

1. **优先用 jcodemunch 导航，替代裸 Read/Grep/Glob**：定位符号（函数/类/常量）、查引用、分析调用链、检索代码时，先经 `route`（自然语言任务→动作）或 `menu`（浏览工具目录）调起 `order` 分发对应动作（`search_symbols` / `find_references` / `get_call_hierarchy` / `get_blast_radius` 等），避免整文件盲读。
2. **会话开头确认索引**：`list_repos` 显示该仓库 `fresh` 可直接检索；若 `stale` 或缺失，先 `index_folder` 重建再查。
3. **检索结果用于定位，不替代事实核验**：符号/行号用于快速跳转；涉及平台对接（§4）、幂等、凭证的结论仍以 `docs/` 与实测记录为准，禁止把检索结果当作合规事实。
4. **敏感信息约束不变**：检索、引用工具输出时仍受 §2 安全红线约束——不打印/不透传 `REG_*`、LBS key、token 等。
5. **索引自维护**：watcher 自动增量更新，无需手动刷新；必要时手动 `jcodemunch-mcp index .`。

## 8. 文档同步规则

1. **代码与文档必须同步**：改接口（services/mock/后端）→ 同步 `docs/04`；改页面结构 → 同步 `docs/03`；改部署/配置 → 同步 README 与 `docs/02`；平台联调新结论 → 追加 `docs/07` 或 `07-full-test`。
2. 大文档（01/02/04）采用"追加修订记录 + 追加章节"的方式更新，**不重写历史结论**；小文档（README、07-full-test、08）可直接改写并升版本号。
3. 每份 docs 文档有自己的版本修订记录表，改动必须加一行。
4. `docs/07`（实测记录）与 V1.5 规范冲突时，**以 07 为准**；同时把差异列入 `docs/06` 请平台修订规范。
5. 本文件（AGENTS.md）规则变更须在本文件内追加修订记录。

## 9. 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| V1.0 | 2026-09-30 | 首版：项目结构、分层、安全红线、编码约定、平台实测事实、Git/测试/文档同步规则 |
| V1.1 | 2026-09-30 | 派单验收口径改为“同项目 6 台同日到期一次性全部当日 09:00 派单”；补充全量一致性核查与 TDesign 依赖移除后的 lint 要求 |
| V1.2 | 2026-10-02 | 同步实际架构：后端落地状态（97 单测）、admin 目录与目录级 lint/test 命令、docs 范围 01—11；gitignore 补本地 AI 工具缓存与备份文件 |
| V1.3 | 2026-10-03 | 新增 §7 AI 代码导航（jcodemunch MCP）：优先符号级检索、索引自维护、敏感信息约束；原 §7/§8 顺延为 §8/§9 |
| V1.4 | 2026-10-03 | §4.4 更正 2.7 查询口径：平台不支持按 `elevatorCode` 查询（以 docs/06 #8 / docs/04 A.0.2 实测为准），改为 factoryNumber/registrationCode/deviceCode |
| V1.5 | 2026-10-03 | 全量代码审核整改后同步：①§1 结构表口径更新（小程序 20 页、后端 Flyway V1—V11 + 137 单测、补 `scripts/fixtures/` 与根目录 `doc_text.txt` 说明）；②§3 目录级检查补 admin 构建的 `TMP/TEMP` 规避办法与 verify 脚本触发条件；③§7 更正 jcodemunch 可用性前提——MCP 工具并非每个会话都加载，开头先确认、不可用即回退 Read/Grep/Glob；④§2.5 敏感文件口径澄清：合成测试 PDF 移至 `scripts/fixtures/`（`scripts/tmp/` 保持 gitignore 且不跟踪任何文件） |
| V1.6 | 2026-10-03 | 已裁模块端点删除后同步计数（后端 Flyway V1—V12、100 条路由、136 单测）；明确**删除端点不删表**（`location_appeal`/`alert_rule`/`alert_record`/`op_log` 等保留以满足留存），恢复交付仍按 §1.2 三处同步 |
