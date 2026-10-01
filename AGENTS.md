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

## 1. 项目结构与分层

```
├─ app.js / app.json / app.wxss   小程序入口（27 页，tabBar 4 个）
├─ pages/                        页面（每页 .js/.json/.wxml/.wxss 四件套）
├─ services/                     业务接口层：页面只调 services，禁止直接 wx.request
├─ utils/request.js              唯一请求封装（Bearer 鉴权、X-Idempotency-Key、401 处理）
├─ utils/offline.js              离线持久化队列（网络恢复自动补传）
├─ config/index.js               环境配置（useMock 开关、apiBaseUrl、LBS key 留空）
├─ constants/index.js            枚举/错误码/业务常量（文案统一从这里取）
├─ mock/                         mock 契约层（server.js 路由 = docs/04 A 部分）
├─ backend/                      Spring Boot 3.3.4 骨架（平台 token 中控 + 转发层）
├─ scripts/                      平台联调 PowerShell/Node 脚本
└─ docs/                         01—08 编号文档（见 §7 文档规则）
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
- **注释**：每个文件顶部一行职责说明；注释只写代码在做什么，以及从代码看不出来的原因；不注明规则/文档出处（不写 `docs/0X §Y`、`TSG`、`平台 2.x`、`AGENTS §`、日期与优先级）；合规红线（凭证不入库、日志脱敏、禁止自动重试）保留一行提示。
- **错误码**：统一用 `constants/index.js` 的 `ERROR_CODES`，禁止页面内硬编码文案。
- **枚举**：维保类别 FM/HM/TM/SM/OY、隐患码 S0—S7、判定方式 NUMERIC/STANDARD/MANUFACTURER/QUALITATIVE 只从 `constants` 引用；隐患码按 S0—S7 连续编码（规范原文 S3 重复为笔误，待平台确认 docs/06 #9）。
- **时间**：存取一律 `yyyy-MM-dd HH:mm:ss`（GMT+8），用 `utils/util.js` 的 formatTime/parseTime（iOS 兼容已处理）；时长 `HH:mm:ss`。
- **幂等**：所有写接口自动携带 `X-Idempotency-Key`（`request.js` 已实现），新写接口不得绕过。
- **微信小程序规范**：新页面四件套齐全并注册进 `app.json`；`requiredPrivateInfos` 变更须同步说明用途；不得引入新 UI 框架（TDesign 已移除，如重新引入须按 docs/08 重新评估并同步文档）。
- **后端**：Java 17 / Spring Boot 3.3.4；统一 `ApiResponse` 返回；配置经 `PlatformProperties`（前缀 `platform.`）从环境变量注入；容器端口 80（`SERVER_PORT`）。

## 4. 平台对接事实（实测为准，勿按规范臆测）

1. 2.1 登录：**GET + URL 查询串**（规范写 POST+Body 有误）。
2. 2.2—2.8：POST + `application/x-www-form-urlencoded` 表单；2.3/2.4 为 **multipart + 文件**（contractFile/certificateFile）。
3. 根路径：`https://tzsb.scjgj.cq.gov.cn:1443/api/wlw/maintenance/`；全部接口需 `Authorization: Bearer`。
4. 2.3 参数名实为 `useUnitName`；2.6 的 `workMan2Id` **必填**（单人作业也传第二人 ID）；2.7 可按 `elevatorCode` 查询且回填未脱敏的 `elevatorAdminister*`/`emergencyPhone`。
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

## 7. 文档同步规则

1. **代码与文档必须同步**：改接口（services/mock/后端）→ 同步 `docs/04`；改页面结构 → 同步 `docs/03`；改部署/配置 → 同步 README 与 `docs/02`；平台联调新结论 → 追加 `docs/07` 或 `07-full-test`。
2. 大文档（01/02/04）采用"追加修订记录 + 追加章节"的方式更新，**不重写历史结论**；小文档（README、07-full-test、08）可直接改写并升版本号。
3. 每份 docs 文档有自己的版本修订记录表，改动必须加一行。
4. `docs/07`（实测记录）与 V1.5 规范冲突时，**以 07 为准**；同时把差异列入 `docs/06` 请平台修订规范。
5. 本文件（AGENTS.md）规则变更须在本文件内追加修订记录。

## 8. 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| V1.0 | 2026-09-30 | 首版：项目结构、分层、安全红线、编码约定、平台实测事实、Git/测试/文档同步规则 |
| V1.1 | 2026-09-30 | 派单验收口径改为“同项目 6 台同日到期一次性全部当日 09:00 派单”；补充全量一致性核查与 TDesign 依赖移除后的 lint 要求 |
| V1.2 | 2026-10-02 | 注释约定改为「只解释代码含义，不注明规则出处」；合规红线仍保留一行提示 |
