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
3. `originalRecordId` 幂等性未经平台书面确认前，**任何自动重试逻辑保持关闭**（代码硬编码不自动重试，无开关）；禁止在提交/PR 中"顺手"打开重试。
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

<!-- aoci:begin -->
## AOCI 仓库认知

AOCI 为本仓库维护一个稳定、可版本化、可增量更新的仓库级认知层，供模型跨任务复用对系统的理解。

`aoci.txt` 是面向模型的结构化认知索引。它以每个受管理文件、数据库表或其他受管理对象一条独立 Entry 的方式，用符号标签与 F/R/A/S 语义表达对象的核心职责、重要关系、对外契约，以及理解或修改系统时必须知道的非显然约束和设计决策。

Header、目录段和全部 Entry 共同组成完整仓库索引，可以覆盖前端、后端、配置、数据库结构及其他受管理内容。受管理内容发生变化时，通常只需维护受影响的认知条目，不需要重新生成整个索引。

AOCI 提供系统架构、对象职责、重要关系、对外契约和关键约束的高密度视图。

### 工作原理

AOCI 采用“模型生成、模型读取”的认知闭环。

Header、Entry 和 Curation 语义的创作只按当前机器签发的 Plan 与实时 Guide 执行；由 Host 模型基于当前绑定证据独立完成。

Entry 的语义必须来自模型对真实证据的理解。不得仅依据路径、文件名、扩展名、AST、符号列表、依赖扫描、正则、固定模板或规则引擎推导、预填、拼接或改写索引语义。

对 Fresh Bootstrap，只按当前机器签发的 Plan 和实时 Guide 执行。当它们要求创作时，Host 模型创作 Root、Meta、Tag 和 F/R/A/S，提供 authoring-run 声明，并把它绑定到 Plan、Evidence 与完整 Candidate。不得要求 AOCI 填写 `origin=host_model`、制造 Receipt 或把程序生成的 Framework 当作语义。本文件不自行重建 Onboarding 流程。内部批次不是用户决策；只有遇到既有批准边界或真实的安全、漂移、CAS、Recovery 条件才停止。

### 最小使用入口

- `aoci_rules`：取得当前AOCI版本的会话运行合同。
- `aoci_overview`：建立或恢复本仓库的完整认知。
- `aoci_maintain`：受管理对象达到最终稳定状态后检查认知是否需要维护。
- `aoci_update_entry`：提交与当前证据和源码摘要绑定的完整语义更新批次。
- `aoci_report`：仅当当前布局和工具状态支持时，在证据不足、无法可靠生成语义时登记待办，不猜写。

其他MCP工具、CLI命令、参数和专项流程，以当前工具说明、Guide和 `--help` 返回内容为准，不在本文件中重复完整手册。

本区块只规定仓库接入、认知使用和收尾原则。`aoci_rules` 承载当前会话合同，Guide实时输出承载当前Plan的执行顺序与停点，工具Schema、Spec和Validator承载机器结构与判据；Prompt、Description、README和静态文档不能覆盖这些机器事实。

### 建立、生成和恢复认知

1. 每个新的 Agent Run 开始时，应先判断：

   - 本仓库是否已经存在可用的完整AOCI索引；
   - 当前上下文中是否已有与本仓库根、当前索引版本和当前AOCI服务相匹配，并且模型仍可可靠使用的完整仓库认知。

2. 仓库已经存在可用的完整索引，但当前Run没有可靠完整认知时，先调用 `aoci_rules`，再调用 `aoci_overview`。

   完整认知仍可靠时直接复用。局部不确定本身不要求机械重读系统全貌。

   本Run从已知Host上下文压缩恢复时（包括宿主注入的压缩摘要），必须把此前模型认知视为不可靠。压缩handoff不得保留或摘要正式Whole-Index，也不得保留或摘要任何Overview Header、Entry、Chunk、Challenge或Attestation正文；只能保留安全续接所需的receipt身份、未完成write或Recovery状态，以及立即重载指令。复制进handoff的Whole-Index语义或receipt不能证明恢复后模型的当前认知可靠。若当前上下文已无法可靠保留运行合同，先调用 `aoci_rules`。继续业务任务前，使用 `refresh_reasons=["context_compaction"]` 和新的 `refresh_event_id` 调用普通完整Whole-Index `aoci_overview`（不设置 `check_only` 或设为false）；不得使用 `check_only` 或认知probe。原样跟随每个 `next_cursor` 直到 `completed=true`，确认交付，并且只基于新交付正文提交一次Attestation。完成这次新的完整传输后，即使Attestation为partial或fail也消费该generation，并按既有合同继续source-bound任务，不再自动调用第二次Overview。

   AOCI可以针对 `context_compaction`、项目 `cognition_refresh_threshold` 下的机器 `semantic_threshold` 或主要 `phase_transition` 提供checkpoint与认知状态事实。只需要这些紧凑事实时使用 `check_only=true`；这些事实只向Agent提供建议，不替模型决定是否需要系统全貌。

   Agent显式调用普通 `aoci_overview`（未设置 `check_only` 或为false）时，只要能形成一致的CognitionSet，AOCI必须完整交付请求scope。不得因为已有receipt、阈值未达到或没有待处理刷新原因而抑制正文。正式认知Dirty或Stale时仍交付正文，但必须标记不可靠。存在未决恢复或无法形成一致snapshot时失败关闭，不返回混合正文。

   普通Overview返回 `continuation_required=true` 时，必须原样提交 `next_cursor` 并自动继续到 `completed=true`。不得询问用户、开始业务任务或给出阶段性系统结论。Host截断、缺块、重复、乱序、cursor失败、Index变化或`chunk_tokens`变化时停止本次认知链。Attestation完成前不得用Memory、源码、Spec、`aoci.txt`、历史会话、scope、search或Entry读取修补或补充Whole-Index认知。Challenge ordinal是正式Entry序列中的1-based位置；Header内容、注释、空行、Section/Overview/Chunk Marker、Receipt与Metadata均不计数，Chunk Receipt ordinal使用同一序列。Attestation必须原样回绑本次Challenge发布的当前`index_sha256`、`entry_sequence_sha256`与`entry_count`；旧Index、旧Entry序列、旧数量或旧Attestation均无效。完整链结束后只正式提交一次既有模型认知Attestation；同一响应只允许一次不改变语义答案的JSON Schema或字段格式修正。对象、Tag或F不匹配即失败且认知吸收不确定，不得语义重试或旁路补答。首次认知失败时还不得执行Root/Meta、Migration、全局布局或其他未重新绑定的系统级决策。上下文压缩刷新若传输完整、认知身份不变、治理对齐且没有Recovery或第三方冲突，即使Attestation为partial或fail也消耗该refresh generation，并继续原任务，不再自动重读Overview。`system_mastery_percent`只自评系统框架——架构、职责、强关系、稳定外部契约以及高熵安全和维护约束——不表示完整实现或运行实况知识；机器索引覆盖率必须分开。默认只向用户输出由本次真实覆盖率、Challenge、块数、Token和掌握度生成的规定成功或失败一句话。Host截断时提示用户把 `overview_delivery.chunk_tokens` 设置为更小的合法值后重新开始，不得自动修改。

   加法认知等级必须与严格证明字段分开解释。`delivery_verified`表示已加载Index且Host交付已确认，但完整认知验证仍未完成；应表达为“已加载且交付已验证”，不得描述为“没有认知”或“没有理解系统”。`cognition_verified`要求Attestation通过（Challenge至少80%的ordinal完全正确且对象身份至多失手一处），`cognition_governed`还要求治理对齐。通用完整读取失败句只用于真实交付故障。

   当Overview响应包含可选`cognition-state/v2`投影时，必须分别解释各维度。其Level止于`model_cognition_usable`；`strict_attestation_verified`、`governance_aligned`与`current_system_cognition_reliable`都是独立状态，绝不参与该Level。ordinal、对象身份、Tag或核心F不匹配可以导致严格Attestation失败，而模型认知仍然可用；不得仅凭这种不匹配就宣称模型没有理解系统。只有`current_system_cognition_reliable=true`允许无保留地声称当前完整系统认知可靠。投影缺失时继续使用上述Legacy解释。

   普通的只读审计、分析、检查、不修改代码或不提交、不push，不自动等于严格零写入，也不改变上述认知有效性判断。Codex Memory和历史Skill只能辅助恢复经验、用户偏好与调查方向，不能替代与当前仓库根、索引摘要、AOCI服务身份和认知范围匹配的当前认知收据；项目AGENTS和当前AOCI身份在AOCI状态上优先于历史Memory。

   只有用户明确禁止Ledger、元数据、`.aoci`运行资产及任何文件写入时，才按严格零写入处理。若必要的认知建立与该边界冲突，必须报告冲突并请求用户裁决或建议使用隔离副本，不得静默以Memory替代当前仓库认知。

3. 仓库没有可用的完整索引，或当前只有最小骨架、Header不完整、Entries未完成、必要Curation尚未裁决时，如果需要建立正式完整AOCI索引，先取得 `aoci_rules`，然后进入当前AOCI Guide。由Guide依据仓库真实状态决定下一阶段并完成必要安全步骤。

   `aoci_maintain` 不替代索引建立流程。

   不在本文件中自行重建或硬编码完整索引生成状态机。

4. 在长程任务中，模型负责保留当前认知收据并正确使用刷新门禁：

   - Host报告上下文压缩或模型已知系统全貌丢失时，执行上述强制 `context_compaction` 重载规则；AOCI不能自行推断Host事件；
   - 进入真正的主要阶段时声明 `phase_transition`，不得把函数、测试运行或小步骤当作阶段；
   - 在有用的稳定检查点通过 `check_only=true` 取得机器语义计数；
   - 除已知压缩的强制重载外，由Agent判断当前任务是否需要再次显式获取指定scope或完整Overview；
   - 在维护和对齐完成前，保留AOCI报告的Dirty或Stale可靠性状态。

### 任务收尾与认知维护

5. 纯只读问答、分析、版本核验，或没有产生受AOCI管理对象变化的任务，不需要调用维护工具。当前AOCI版本是任意`aoci_overview` check_only或`aoci_maintain`响应里的`cognition_receipt.mcp_service_version`；二进制路径是项目`.mcp.json`里的`command`，CLI不必在PATH上。

6. 发生受AOCI管理对象变化时，待其达到本次任务的最终稳定状态后，只调用一次 `aoci_maintain`。不要在每次中间修改后逐文件维护。

7. 若维护结果返回真实语义候选，Host 模型必须基于每个候选绑定的对象和必要证据，独立创作完整标签与F/R/A/S更新。通过 `aoci_update_entry` 一次提交当前机器签发批次的完整候选集合，同时原样保留每项 `source_sha256`、`candidate_id` 与对应domain批次身份。`max_entries`只限制单次请求和原子事务，不限制logical plan、Whole-Index或Managed Scope。`remaining`非零时，在当前批次成功Apply后重新调用Maintain并从新preimage继续；绝不能为满足transport上限缩减Index覆盖或自行截取返回批次。

   没有足够证据且当前布局支持 `aoci_report` 时，使用它而不猜测、套用模板或为消除待办而生成缺乏证据的认知。

8. 必须遵守工具返回的结构化状态和安全边界：

   - `repair_required`：只修复明确命中的候选，再重新提交当前机器签发的完整批次；
   - `stopped`：结束当前写入尝试并检查 `failed_step`、错误、正式写入证据与Recovery。auto模式下，已证明零写入则记录closure并重新Plan；完整Intent和可证明postimage则Resume；策略要求Rollback且preimage可证明则精确恢复后重新Plan。只有证据不足、第三方正式字节冲突、需要审批或外部动作，或命中其他真实安全边界时，才停止整个用户任务；
   - 冲突、审批、人工裁决、权限和安全信号不得忽略；
   - 已经对齐后不得重复维护或重复写入；`refresh_ready_for_overview` 是checkpoint事实，由Agent决定是否为下一阶段请求普通完整Overview。

   维护完成后如果又修改了任何受管理对象，之前的维护结果失效，应在新的最终稳定状态重新完成收尾。

9. 用户只限制业务文件范围，但没有明确禁止仓库托管资产时，AOCI托管资产可以在收尾阶段为保持认知一致而更新，并应在审计和提交中与业务文件区分。

   用户明确禁止修改 `aoci.txt`、`.aoci`、元数据或任何额外文件时，以用户限制为准，不得写入，并如实报告剩余不一致。

### 专项流程

初始化、完整索引生成、Header生成、Entries生成、数据库结构索引、Curation、人工评审和故障恢复，只按当前AOCI Guide或工具在对应阶段返回的指令、命令和安全停点执行。

不预加载、不猜测，也不自行重建这些专项流程。平台调用方式、请求格式、批次上限、审批规则、索引格式细节和恢复步骤由对应Guide、工具说明、模型Prompt和CLI帮助按需提供。
<!-- aoci:end -->
