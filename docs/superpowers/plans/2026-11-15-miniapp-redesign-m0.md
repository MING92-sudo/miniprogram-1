# 博威无纸化小程序重构 M0 映射基线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 产出 M0 映射基线文档（三张状态机映射表 + 49 页路由对照 + 106 唯一权限码覆盖 + 原型资产提取件）与结构守卫脚本，为 M1 后端底座提供逐字实施依据。

**Architecture:** 纯调查 + 文档 + 资产提取里程碑，不改业务代码。映射表以代码实测状态字面量为旧态基准、蓝图 V1.3 状态机为新态基准；守卫脚本 `scripts/verify-miniapp-m0.js` 对每张表做集合完整性校验（缺口即退出码 1，输出 JSON 计数）；资产管线隔离在 `scripts/asset-build/`（独立 package.json，不污染根依赖）。

**Tech Stack:** Node 24 CommonJS（守卫/提取脚本）、fantasticon（iconfont 构建）、opentype.js（字体 cmap 缺口检测）、fonttools（可选，子集扩字）、Java 17 后端只读核对。

**Spec:** `docs/superpowers/specs/2026-11-15-miniapp-redesign-design.md`（V1.0，§1–§7 已确认）

## Global Constraints

- 品牌 = 博威无纸化；所有页面不允许出现英文（装饰微标改中文；权限码不上页面；业务编号属数据编码豁免）。
- 签退即上报 2.6 不变（AGENTS §3.5）；归档/修订不重报；禁止任何自动重试（AGENTS §2.4）。
- 不改平台调用链路 2.1—2.8；openid 只信服务端换取；凭证只进环境变量/.env。
- 接口三处同步（services + mock/server.js + docs/04 A）；M0 不新增端点，不触发三处同步。
- 小程序 CommonJS、2 空格、单引号；根 `npm run lint` 必过；不引入新 UI 框架。
- 迁移严禁自动 TRUNCATE/DELETE；旧表不删（留存口径）。
- 取证分离：结构等价取证（脚本计数）与真实绘制取证（截图目视）分开标注，不以结构冒充目视。
- Windows 环境子代理不可用 → inline 执行 + 每任务自审留痕。
- 不擅自安装系统依赖：fonttools/brotli 缺失时停下报告用户裁决，不静默 pip install。

## File Structure

| 文件 | 职责 |
|---|---|
| `docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md` | M0 唯一交付文档：§1 记录映射 / §2 移交映射 / §3 急修映射 / §4 路由对照 / §5 权限覆盖 / §6 资产清单 |
| `scripts/verify-miniapp-m0.js` | 结构守卫：7 项集合完整性检查，JSON 计数输出，缺口退出码 1 |
| `assets/brand/logo.jpg` | 公司 logo 原图（备查，页面仅渲染圆形徽标裁切件） |
| `scripts/asset-build/extract-symbols.js` | 从原型 index.html 提取 64 个 FA symbol → svg/ |
| `scripts/asset-build/package.json` | 隔离依赖：fantasticon + opentype.js |
| `scripts/asset-build/build-icons.js` | svg → woff2 + codepoints → assets/icons/icons.wxss |
| `scripts/asset-build/extract-zcool.js` | 提取原型 ZCOOL 子集 + cmap 缺口检测 → assets/fonts/ + zcool-gap.json |
| `assets/icons/icons.wxss` | iconfont base64 + 64 个 .i-xxx 类（M2 消费） |
| `assets/fonts/zcool-proto-subset.*` | 原型 75 字子集原件 |
| `assets/fonts/zcool-subset.*` | 扩字后子集（覆盖博威无纸化及标题用字） |
| `assets/fonts/zcool-gap.json` | 缺口检测报告（missing 必须为空或经用户裁决的兜底记录） |

**状态基准（2026-11-15 实测，执行时复核行号）**：
- WorkOrder：`PENDING/PROCESSING/DONE`（WorkOrderService.java:321,375,735）
- Handover：`INIT/DOC_SUBMITTING/INSPECTING/RECTIFYING/PENDING_CONFIRM/ARCHIVED`（HandoverService.java:48–53）
- Fault：`OPEN/ASSIGNED/CLOSED`（DirectoryService.java:181,209,414；RecordPdfService.java:576–584）
- PermissionMatrix：110 个常量、**106 个唯一码**（86 v2 三态 + 20 旧码独有；filing:write/region:write/report:retry/template:write 四字符串新旧共享）

---

### Task 1: M0 文档骨架 + 维保记录状态机映射 + 守卫脚本基座

**Files:**
- Create: `docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md`
- Create: `scripts/verify-miniapp-m0.js`
- Read: `backend/src/main/java/com/cqwlw/maintenance/service/WorkOrderService.java`、`backend/src/main/resources/db/migration/V38__m4_work_domain.sql`、蓝图 `C:\Users\18223\Desktop\UI\roles-permissions.md` §6.2

**Interfaces:**
- Produces: M0 文档 §1 表（旧态/节点 → 蓝图态 → 新实现口径 → 代码出处）；守卫脚本 `check(name, missing, total)` 与 `section(n)` 切片函数，后续 Task 2–6 追加检查项复用。

- [ ] **Step 1: 复核记录域状态字面量**

Run: `grep -noE 'status = "[A-Z_]+"|"[A-Z_]+"\.equals\(o\.status\)' backend/src/main/java/com/cqwlw/maintenance/service/WorkOrderService.java`
Expected: 仅 PENDING/PROCESSING/DONE 三值；行号与基准一致（漂移则更新基准并记录）。

- [ ] **Step 2: 创建 M0 文档，写入 §1 记录映射表**

```markdown
# 博威无纸化小程序重构 M0 映射基线

> V1.0 · 2026-11-15 · 依据规格 2026-11-15-miniapp-redesign-design.md · 守卫：scripts/verify-miniapp-m0.js

## 1 维保记录状态机映射

| 旧态/节点 | 代码出处 | 蓝图 §6.2 对应 | 新实现口径 |
|---|---|---|---|
| PENDING | WorkOrderService.java:321 | 计划生成→接单 | 不变；接单置 PROCESSING |
| PROCESSING | WorkOrderService.java:375 | 作业中（点检/拍照/实测） | 不变；签到/动态码/清单合规主干逐字不动 |
| 主检签名 | POST /work-orders/{id}/signature（V23） | 主检签名提交→待配合人签字 | 不变；新增配合退回端点 signature-refuse（无上报副作用） |
| 配合签名 | POST /work-orders/{id}/signature（V23） | 配合人核对签名→待复核/待使用单位签字 | 不变；各签各的 |
| DONE | WorkOrderService.java:735 | 双签完成=签退→生命周期入口 | 签退即冻结快照+2.6 即报（D2）；DONE 后叠加 lifecycle_status |
| 新增 PENDING_REVIEW | — | 待复核 | sys_config 键 record_review_enabled，默认关=免复核直达；主管 record:review |
| 新增 PENDING_CLIENT_SIGN | — | 待使用单位签字 | sign-request 发起（主检/主管，门控双签齐） |
| 新增 PENDING_CLIENT_SIGNATURE | — | 待使用单位签署 | 一次性签署任务 taskId+token（复用 sign-view/confirm-by-token） |
| 新增 SIGNED | — | 已签署 | 触发自动归档 |
| 新增 ARCHIVED | — | 已归档 | PDF+SHA-256（record:archive 系统/主管）；不触发平台调用 |
| 新增 REFUSED_RETURNED | — | 拒签→待整改 | 拒签必填原因，退回维保人员重新走签字链路 |
| 修订 | 管理端 amend-v2（V38） | 已归档→修订 | 复用；经理+平台双人；新版本+新哈希；不重报 2.6（P-5） |

## 2 移交状态机映射

（Task 2 写入）

## 3 急修/报修状态机映射

（Task 3 写入）

## 4 路由对照（49 页）

（Task 4 写入）

## 5 权限码覆盖（106 唯一）

（Task 5 写入）

## 6 原型资产提取清单

（Task 6 写入）
```

- [ ] **Step 3: 创建守卫脚本基座 + 记录域检查**

```js
// scripts/verify-miniapp-m0.js
// M0 映射基线结构守卫（结构等价取证）：集合完整性校验，缺口退出码 1
const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '..')
const M0 = fs.readFileSync(path.join(ROOT, 'docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md'), 'utf8')
const SPEC = fs.readFileSync(path.join(ROOT, 'docs/superpowers/specs/2026-11-15-miniapp-redesign-design.md'), 'utf8')
const results = []
function check(name, missing, total) {
  results.push({ name, total, missingCount: missing.length, missing })
  if (missing.length) process.exitCode = 1
}
function section(n) {
  const re = new RegExp('## ' + n + ' [^\\n]*\\n([\\s\\S]*?)(?=\\n## \\d |$)')
  const m = M0.match(re)
  return m ? m[1] : ''
}
// 1 记录域：旧态全覆盖（严格双形态：status 赋值 或 "X".equals(o.status)；双捕获组归一）
const woSrc = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/service/WorkOrderService.java'), 'utf8')
const recordStates = [...new Set([...woSrc.matchAll(/status = "([A-Z_]+)"|"([A-Z_]+)"\.equals\(o\.status\)/g)].map(m => m[1] || m[2]))]
check('record-states', recordStates.filter(s => !section(1).includes('| ' + s + ' ')), recordStates.length)
console.log(JSON.stringify(results, null, 2))
```

正则说明：仅匹配 `status = "X"` 赋值与 `"X".equals(o.status)` 比较两形态。文件内其余大写串（S0–S7/FM/HM/TM/SM/OY/ASSISTANT/PRINCIPAL/UNKNOWN/SELF_COLLECTED/NA/ABNORMAL 及 Fault 查询的 OPEN）均不命中，避免误报（2026-11-15 预检实测）。

- [ ] **Step 4: 运行守卫验证记录域检查生效**

Run: `node scripts/verify-miniapp-m0.js`
Expected: JSON 输出 `record-states` total=3 missingCount=0；故意删表中一行重跑应 missingCount=1 退出码 1（验后恢复）。

- [ ] **Step 5: 根 lint + 提交**

Run: `npm run lint`
```bash
git add docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md scripts/verify-miniapp-m0.js
git commit -m "docs: M0 记录状态机映射与守卫基座（PENDING/PROCESSING/DONE→蓝图生命周期）"
```

---

### Task 2: 移交状态机映射

**Files:**
- Modify: `docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md` §2
- Modify: `scripts/verify-miniapp-m0.js`（追加 checkHandover）
- Read: `HandoverService.java:40–80`、`HandoverChecklistService.java`（判定五路径）、蓝图 roles-permissions.md §6.1

**Interfaces:**
- Consumes: Task 1 的 `check/section/literals`。
- Produces: §2 表（6 旧态 + 判定/容缺/作废分支 → 蓝图 §6.1）；M1 新增态清单（待安装部审核、作废）。

- [ ] **Step 1: 复核移交状态字面量**

Run: `grep -noE '"[A-Z_]{3,}"' backend/src/main/java/com/cqwlw/maintenance/service/HandoverService.java | head -20`
Expected: 状态六值 INIT/DOC_SUBMITTING/INSPECTING/RECTIFYING/PENDING_CONFIRM/ARCHIVED（48–53 行）；其余大写串为项类/角色/节点名，不入状态表。

- [ ] **Step 2: 写入 §2 映射表**

```markdown
| 旧态/节点 | 代码出处 | 蓝图 §6.1 对应 | 新实现口径 |
|---|---|---|---|
| INIT | HandoverService.java:48 | 草稿 | 不变；create 页（屏 09） |
| DOC_SUBMITTING | HandoverService.java:49 | 草稿→待安装部审核（资料提交） | upload 页 D1–D20（3 强制卡点）；齐全提交进入新增审核态 |
| 新增 PENDING_INST_REVIEW | — | 待安装部审核 | review 页 INST_MGR 审核签章/退回（原因+期限）；M1 新增态+端点 |
| INSPECTING | HandoverService.java:50 | 待维保接收→验收中 | 接收指派（MAINT_SUP handover:receive）后进入；inspect 74 项 G01–G13 逐项留证 |
| RECTIFYING | HandoverService.java:51 | 退回整改 | 1:1 item_no 整改不可增删；逐条复验全 PASS 才回待确认（C-12） |
| PENDING_CONFIRM | HandoverService.java:52 | 待双方签字 | 判定 QUALIFIED/CONDITIONAL 后进入；sign 页五节点；☆NA 经理审批 |
| ARCHIVED | HandoverService.java:53 | 已生效→已归档 | 生效→台账在保+首保计划（沿用现有口径）；平台 2.3/2.4 沿用不新增 |
| 判定 DISQUALIFIED | HandoverChecklistService 7.2/7.3 | 退回整改 | 重要项任一不合格/一般项>8；CONDITIONAL 三条件齐备 |
| 容缺分支 | handover_doc 缺件标记 | 容缺受理 | 缺件补传完成前不得进入已生效 |
| 新增 VOIDED | — | 任意未生效→作废 | 安装主管+维保经理+平台审计；原因必填；M1 新增端点 |
| filing 四页 | pages/filing/*（W2/V29） | 蓝图无独立路由 | 保留扩展迁 pkg-handover/filing-*；archive_source=2+首保+3 日不变 |
```

- [ ] **Step 3: 守卫追加移交检查**

```js
// 2 移交域：旧态全覆盖（基准六值取自 HandoverService.java:48–53 状态常量块）
const hoSrc = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/service/HandoverService.java'), 'utf8')
const hoBlock = hoSrc.split('\n').slice(47, 53).join('')
const handoverStates = [...new Set((hoBlock.match(/"([A-Z_]+)"/g) || []).map(s => s.slice(1, -1)))]
check('handover-states', handoverStates.filter(s => !section(2).includes('| ' + s + ' ')), handoverStates.length)
check('handover-baseline', handoverStates.length === 6 ? [] : ['expect 6 got ' + handoverStates.length], 6)
```

行号漂移防护：若 48–53 行切片取不足六值（handover-baseline 缺口），先 grep 定位状态常量块新行号，更新切片区间与基准注释，再复跑；不得改期望值迁就。

- [ ] **Step 4: 运行守卫**

Run: `node scripts/verify-miniapp-m0.js`
Expected: record-states 与 handover-states 均 missingCount=0。

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md scripts/verify-miniapp-m0.js
git commit -m "docs: M0 移交状态机映射（6 旧态+审核/作废新增态+容缺/判定分支）"
```

---

### Task 3: 急修/报修状态机映射

**Files:**
- Modify: M0 文档 §3；`scripts/verify-miniapp-m0.js`（追加 checkFault）
- Read: `DirectoryService.java:175–230,360–420`、`DirectoryController.java:76–120`、蓝图 §6.3

**Interfaces:**
- Consumes: Task 1 守卫基座。
- Produces: §3 表（3 旧态→七态扩展）；M1 急修缺口端点清单（到场打卡/提交/确认/拒绝/取消/升级/评价）。

- [ ] **Step 1: 复核急修状态字面量**

Run: `grep -noE 'status = "[A-Z_]+"|"[A-Z_]+"\.equals\(f\.status\)' backend/src/main/java/com/cqwlw/maintenance/service/DirectoryService.java`
Expected: OPEN/ASSIGNED/CLOSED 三值（181/209/414）。

- [ ] **Step 2: 写入 §3 映射表**

```markdown
| 旧态/节点 | 代码出处 | 蓝图 §6.3 对应 | 新实现口径 |
|---|---|---|---|
| OPEN | DirectoryService.java:181 | 待接单 | 抢单（order:grab 本区域）/派单（order:dispatch 主管/经理）→ASSIGNED |
| ASSIGNED | DirectoryService.java:209 | 已接单 | 新增到场打卡节点（order:arrive GPS+时间戳）→处置中 |
| 新增 PROCESSING | — | 处置中 | 到场后进入；维修过程字段沿用 PUT /faults/{id} |
| 新增 PENDING_CONFIRM | — | 待使用单位确认 | 提交急修单（order:submit）后进入；CLIENT_SIGNER 确认（管理员须兼任） |
| CLOSED | DirectoryService.java:414 | 已闭环 | 确认后系统闭环（order:close 系统/主管）；维保人员只读；评价入口开放 |
| 新增 REFUSED | — | 待整改 | 拒绝必填原因，退回处置 |
| 新增 CANCELLED | — | 已取消 | 接单前单位管理员/接单后主管；原因必填（order:cancel） |
| 升级/改派 | 新增 SLA 任务 | 改派/升级 | order:escalate 阈值 config:manage；超时通知上级 |
| 到场时间现状 | DirectoryService.java:224 | 到场打卡 | 现以最早签到推导 arrivedAt；新增显式打卡节点后以打卡为准，签到推导兜底留痕 |
| 评价 | 无端点 | 已闭环→待评价 | 新增 POST /faults/{id}/evaluate（client:evaluate，1–5 星+文字） |
```

- [ ] **Step 3: 守卫追加急修检查**

```js
// 3 急修域：旧态全覆盖 + 漂移检测（严格双形态，排除 BWJX 单号前缀等大写串误报）
const faultStates = ['OPEN', 'ASSIGNED', 'CLOSED']
const faultSrc = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/service/DirectoryService.java'), 'utf8')
const faultFound = [...new Set([...faultSrc.matchAll(/status = "([A-Z_]+)"|"([A-Z_]+)"\.equals\(f\.status\)/g)].map(m => m[1] || m[2]))]
check('fault-states', faultStates.filter(s => !section(3).includes('| ' + s + ' ')), faultStates.length)
check('fault-drift', faultFound.filter(s => !faultStates.includes(s)), faultFound.length)
```

- [ ] **Step 4: 运行守卫**

Run: `node scripts/verify-miniapp-m0.js`
Expected: 三项检查 missingCount=0。

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md scripts/verify-miniapp-m0.js
git commit -m "docs: M0 急修状态机映射（3 旧态→七态+到场/评价/升级缺口端点清单）"
```

---

### Task 4: 路由对照验证（49 页）

**Files:**
- Modify: M0 文档 §4；`scripts/verify-miniapp-m0.js`（追加 checkRoutes）
- Read: `app.json`、规格 §1.4

**Interfaces:**
- Consumes: 规格 §1.4 映射表（49 新路由）。
- Produces: §4 对照表 = 规格 §1.4 全表展开（pkg-admin 花括号展开为 7 行）+ 每旧页存在性证据；守卫保证 app.json 40 页 ⊆ 旧页列、规格 49 路由 ⊆ 新路由列。

- [ ] **Step 1: 生成 §4 表**

将规格 §1.4 表逐行复制入 M0 §4，pkg-admin 行展开为 projects/contracts/users/roles/stats/audit/settings 七行；表头加「旧页存在」列（执行时 `ls pages/...` 逐页核对，新页填「新建」）。

- [ ] **Step 2: 守卫追加路由检查**

```js
// 4 路由：旧页全覆盖 + 新路由全覆盖
const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
const s4 = section(4)
check('routes-old', app.pages.filter(p => !s4.includes(p)), app.pages.length)
const spec14 = SPEC.split('### 1.4')[1].split('### 1.5')[0]
const specRoutes = new Set()
for (const m of spec14.matchAll(/`((?:pages|pkg-[a-z]+)\/[^`]+)`/g)) {
  const p = m[1]
  const brace = p.match(/\{([^}]+)\}/)
  if (brace) brace[1].split(',').forEach(x => specRoutes.add(p.replace(brace[0], x)))
  else specRoutes.add(p)
}
check('routes-new', [...specRoutes].filter(p => !s4.includes(p)), specRoutes.size)
check('routes-count', specRoutes.size === 49 ? [] : ['expect 49 got ' + specRoutes.size], 49)
```

- [ ] **Step 3: 运行守卫**

Run: `node scripts/verify-miniapp-m0.js`
Expected: routes-old total=40 missingCount=0；routes-new total=49 missingCount=0；routes-count 通过。若 count≠49，先核对规格表与展开逻辑，修正文档而非改期望值。

- [ ] **Step 4: 提交**

```bash
git add docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md scripts/verify-miniapp-m0.js
git commit -m "docs: M0 路由对照验证（40 旧页⊆对照表、49 新路由⊆对照表）"
```

---

### Task 5: 权限码覆盖（106 唯一）

**Files:**
- Modify: M0 文档 §5；`scripts/verify-miniapp-m0.js`（追加 checkPerms）
- Read: `PermissionMatrix.java`、蓝图 roles-permissions.md §12 权限覆盖表

**Interfaces:**
- Consumes: PermissionMatrix 106 唯一码。
- Produces: §5表每码一行：`权限码 | 承载（路由直达/页内动作/管理端/系统自动/冻结旧码） | 路由或端点 | 允许角色`；M2 RouteMeta 与 M1 端点门控据此登记。

- [ ] **Step 1: 提取唯一码清单**

Run: `grep -oE 'public static final String [A-Z_0-9]+ = "[a-z_]+:[a-z_]+"' backend/src/main/java/com/cqwlw/maintenance/auth/PermissionMatrix.java | sed 's/.*= "//;s/"//' | sort -u`
Expected: 106 行。

- [ ] **Step 2: 逐码分类写入 §5**

分类规则（蓝图 §12 口径）：
- 路由直达：码绑定 §4 路由（如 handover:inspect → pkg-handover/pages/inspect/index）
- 页内动作：复用已有路由的按钮级动作（如 order:grab → pages/order/list/index 行内次按钮）
- 管理端承接：小程序无入口（如 record:amend、template:write、region:write、platform:worker_register）
- 系统自动：无 UI（如 record:archive、order:close、order:escalate、platform:sync）
- 冻结旧码：20 旧码独有字符串标「冻结·LegacyCapabilityTranslator 桥接」，承载按桥接后语义登记
- 语义反转注意：旧 order:read/write = 维保工单；新 order:view_pool/grab/... = 急修；两系分行登记不合并

- [ ] **Step 3: 守卫追加权限检查**

```js
// 5 权限码：106 唯一全覆盖
const pmSrc = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/cqwlw/maintenance/auth/PermissionMatrix.java'), 'utf8')
const codes = [...new Set([...pmSrc.matchAll(/public static final String [A-Z_0-9]+ = "([a-z_]+:[a-z_]+)"/g)].map(m => m[1]))]
const s5 = section(5)
check('perm-codes', codes.filter(c => !s5.includes('| ' + c + ' ')), codes.length)
check('perm-count', codes.length === 106 ? [] : ['expect 106 got ' + codes.length], 106)
```

- [ ] **Step 4: 运行守卫**

Run: `node scripts/verify-miniapp-m0.js`
Expected: perm-codes total=106 missingCount=0。

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md scripts/verify-miniapp-m0.js
git commit -m "docs: M0 权限码覆盖（106 唯一码逐码承载分类，含冻结旧码桥接标注）"
```

---

### Task 6: 原型资产提取（logo/图标/字体）

**Files:**
- Create: `assets/brand/logo.jpg`、`scripts/asset-build/{package.json,extract-symbols.js,build-icons.js,extract-zcool.js}`、`assets/icons/icons.wxss`、`assets/fonts/*`
- Modify: M0 文档 §6；`scripts/verify-miniapp-m0.js`（追加 checkAssets）
- Read: 原型 `C:\Users\18223\Desktop\UI\index.html`（:root L23–42、symbol 块、@font-face）、logo 附件 `C:\Users\18223\.qoder-cn\tmp\C--Users-18223-WeChatProjects-miniprogram-1\images\240ae017-84a9-4398-bf19-71954ef389a1\4ba8707b-0f8f-4af7-b0d0-2c9daaa641ef.jpg`

**Interfaces:**
- Produces: M2 直接消费的 assets/icons/icons.wxss（64 类）、assets/fonts/zcool-subset.*（覆盖品牌与标题用字）、assets/brand/logo.jpg + 徽标裁切参数（圆形区域 bbox 实测值）。

- [ ] **Step 1: logo 入包 + 裁切参数实测**

```bash
mkdir -p assets/brand
cp "C:/Users/18223/.qoder-cn/tmp/C--Users-18223-WeChatProjects-miniprogram-1/images/240ae017-84a9-4398-bf19-71954ef389a1/4ba8707b-0f8f-4af7-b0d0-2c9daaa641ef.jpg" assets/brand/logo.jpg
```

用 Read 工具目视 logo.jpg，记录圆形徽标 bbox（预期约 y 20–330 / 450，BOWEI 字母在下方）；§6 写入裁切口径：M2 以 CSS background-image(data URI) + background-size:100% auto + 视高=宽×(bbox 高/450) 渲染圆形部分，字母不上页。若 tmp 附件已清理，停下请用户重供，不猜测路径。

- [ ] **Step 2: 提取 64 symbol → SVG**

```js
// scripts/asset-build/extract-symbols.js
const fs = require('fs')
const path = require('path')
const html = fs.readFileSync('C:/Users/18223/Desktop/UI/index.html', 'utf8')
const outDir = path.join(__dirname, 'svg')
fs.mkdirSync(outDir, { recursive: true })
let n = 0
for (const m of html.matchAll(/<symbol id="(i-[^"]+)" viewBox="0 0 512 512">([\s\S]*?)<\/symbol>/g)) {
  fs.writeFileSync(path.join(outDir, m[1] + '.svg'),
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">' + m[2] + '</svg>')
  n++
}
console.log(JSON.stringify({ symbols: n }))
if (n !== 64) process.exit(1)
```

Run: `node scripts/asset-build/extract-symbols.js` → Expected: {"symbols":64}。

- [ ] **Step 3: 隔离依赖 + iconfont 构建**

```json
// scripts/asset-build/package.json（实际落地依赖；fantasticon 因其内部 path.join 反斜杠 pattern 被 glob v10 当转义符致 0 匹配而弃用）
{ "name": "asset-build", "private": true, "dependencies": { "opentype.js": "^1.3.4", "subset-font": "^2.3.0", "svg2ttf": "^6.0.3", "svgicons2svgfont": "^12.0.0", "ttf2woff2": "^5.0.0" } }
```

```bash
cd scripts/asset-build && npm install
```

确认忽略：检查根 `.gitignore` 是否覆盖 `scripts/asset-build/node_modules/` 与 `scripts/asset-build/out/`（`node_modules` 通常全局忽略）；未覆盖则追加这两行。`package-lock.json` 入库保证可复现。

```js
// scripts/asset-build/build-icons.js（实际落地：流式管线，以仓库内脚本为准）
// svgicons2svgfont → svg2ttf → ttf2woff2；关键 API 事实：
// 1) 写入对象本身必须是可读流，metadata 挂在流上：contents.metadata = { name, unicode: [String.fromCodePoint(cp)] }
// 2) 码位确定性：0xF101 + 排序索引；类名 = svg 文件名（i-xxx），与原型 #i-xxx 逐字一致
// 3) 输出 assets/icons/icons.wxss：@font-face base64 woff2 + 每类 .i-xxx:before
const fs = require('fs')
const path = require('path')
const SVGIcons2SVGFontStream = require('svgicons2svgfont')
const svg2ttf = require('svg2ttf')
const ttf2woff2 = require('ttf2woff2')
// …完整实现见 scripts/asset-build/build-icons.js（已入库）
```

Run: `node scripts/asset-build/build-icons.js` → Expected: {"glyphs":64}；`grep -c ':before' assets/icons/icons.wxss` = 64，类名形如 `.i-elevator:before`。

- [ ] **Step 4: ZCOOL 子集提取 + 扩字 + 缺口检测**

**关键约束**：opentype.js 不支持 WOFF2 解码，不能直接解析原型内嵌子集做 cmap 检测。因此缺口检测一律在**扩字后的 TTF 子集**上做；原型子集只提取存档不解析。

```js
// scripts/asset-build/extract-zcool.js
const fs = require('fs')
const path = require('path')
const html = fs.readFileSync('C:/Users/18223/Desktop/UI/index.html', 'utf8')
const m = html.match(/font-family:'ZCOOL QingKe HuangYou'[^}]*url\(data:([^;]+);base64,([A-Za-z0-9+/=]+)\)/)
if (!m) { console.error('未找到 ZCOOL 内嵌子集'); process.exit(1) }
const ext = m[1].includes('woff2') ? 'woff2' : m[1].includes('woff') ? 'woff' : 'ttf'
const dir = path.join(__dirname, '../../assets/fonts')
fs.mkdirSync(dir, { recursive: true })
fs.writeFileSync(path.join(dir, 'zcool-proto-subset.' + ext), Buffer.from(m[2], 'base64'))
const REQUIRED = '博威无纸化电梯维保作业系统工作台移交验收半月保急修报修档案记录签字归档统计审计配置组织项目合同人员角色待办评价我的登录无权限空态加载失败早安今日任务班次已同步周五城东大厦客梯货梯困人救援完成进行中合格异常整改复验容缺审核资料上传发起双方使用单位签署确认拒绝原因版本修订导出搜索筛选全部本周一次合格率超时工单监管平台数据同步正常'
fs.writeFileSync(path.join(__dirname, 'required.txt'), [...new Set(REQUIRED)].join(''))
console.log(JSON.stringify({ protoExt: ext, requiredChars: [...new Set(REQUIRED)].length }))
```

Run: `node scripts/asset-build/extract-zcool.js` → 记录 protoExt 与 requiredChars。

扩字与检测（实际落地路径，2026-11-15 裁决）：
1. 下载 OFL 全字到 gitignored 目录：`curl -sL -o scripts/tmp/ZCOOLQingKeHuangYou-Regular.ttf https://raw.githubusercontent.com/google/fonts/main/ofl/zcoolqingkehuangyou/ZCOOLQingKeHuangYou-Regular.ttf`（OFL 1.1 无保留名，子集化沿用原名合规；构建期取材，交付物零外链）。
2. **fonttools 不可用时的既定替代**（本机 python 为 Windows Store stub）：用 `subset-font`（harfbuzz WASM，npm 隔离依赖，不安装系统依赖）：`subsetFont(fullTtfBuffer, requiredText, { targetFormat: 'truetype' })` → assets/fonts/zcool-subset.ttf（注意 fontverter 目标格式名为 truetype 而非 ttf）。实现见 scripts/asset-build/subset-zcool.js。
3. 缺口检测：opentype.js 解析 **TTF 子集**（不解 WOFF2）比对 required.txt → zcool-gap.json；missing 非 0 即退出码 1。实现见 scripts/asset-build/check-zcool-gap.js。若 subset-font 亦不可用，停下报告用户裁决（pip install fonttools brotli 或系统栈兜底留视觉 delta），不静默安装。

```js
// scripts/asset-build/check-zcool-gap.js（TTF 子集路径）
const fs = require('fs')
const path = require('path')
const opentype = require('opentype.js')
const dir = path.join(__dirname, '../../assets/fonts')
const buf = fs.readFileSync(path.join(dir, 'zcool-subset.ttf'))
const font = opentype.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength))
const required = [...new Set(fs.readFileSync(path.join(__dirname, 'required.txt'), 'utf8'))]
const missing = required.filter(ch => !font.charToGlyphIndex(ch))
fs.writeFileSync(path.join(dir, 'zcool-gap.json'), JSON.stringify({ required: required.length, missing }, null, 2))
console.log(JSON.stringify({ required: required.length, missingCount: missing.length, missing: missing.join('') }))
if (missing.length) process.exit(1)
```

Run: `node scripts/asset-build/check-zcool-gap.js` → Expected: missingCount=0。

- [ ] **Step 5: 守卫追加资产检查 + §6 清单**

```js
// 6 资产：logo/64 图标/字体缺口
const assets = []
if (!fs.existsSync(path.join(ROOT, 'assets/brand/logo.jpg'))) assets.push('logo.jpg')
const iconCss = fs.existsSync(path.join(ROOT, 'assets/icons/icons.wxss'))
  ? fs.readFileSync(path.join(ROOT, 'assets/icons/icons.wxss'), 'utf8') : ''
const glyphCount = (iconCss.match(/\.i-[a-z0-9-]+:before/g) || []).length
if (glyphCount !== 64) assets.push('icons glyphs=' + glyphCount)
const gap = fs.existsSync(path.join(ROOT, 'assets/fonts/zcool-gap.json'))
  ? JSON.parse(fs.readFileSync(path.join(ROOT, 'assets/fonts/zcool-gap.json'), 'utf8')) : { missing: ['gap.json 缺失'] }
if (gap.missing.length && !gap.userApprovedFallback) assets.push('zcool missing=' + gap.missing.join(''))
check('assets', assets, 3)
```

§6 写入：令牌表引用规格 §2.1（不重复维护）；logo 裁切 bbox 实测值；图标 64 类名清单；字体子集字符数/缺口报告结论；许可署名口径（CC BY 4.0 + OFL 1.1，落「我的」页中文可见文本）。

- [ ] **Step 6: 运行守卫 + 提交**

Run: `node scripts/verify-miniapp-m0.js` → Expected: 全部检查 missingCount=0。
```bash
git add assets/brand/logo.jpg assets/icons/icons.wxss assets/fonts/ scripts/asset-build/extract-symbols.js scripts/asset-build/build-icons.js scripts/asset-build/extract-zcool.js scripts/asset-build/check-zcool-gap.js scripts/asset-build/package.json scripts/asset-build/package-lock.json docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md scripts/verify-miniapp-m0.js
git commit -m "feat: M0 原型资产提取（logo 入包+64 图标 iconfont+ZCOOL 子集扩字与缺口检测）"
```

注意：`scripts/asset-build/node_modules/`、`out/`、`svg/` 与 `scripts/tmp/` 不入库（确认 .gitignore 覆盖 scripts/tmp；asset-build 生成物若需保留 svg 源则入库 svg/，node_modules/out 必忽略）。

---

### Task 7: M0 门禁 + 文档回写 + 收尾

**Files:**
- Modify: `docs/12-未完成事项清单.md`（新增小程序重构 H 组 M0—M6 行，M0 标完成）
- Modify: 规格 §4.6/§7 M0 行「86 权限码」→「106 唯一码（86 v2 + 20 旧码独有）」
- Modify: M0 文档头部（门禁结果 JSON 摘要 + 取证类型标注）

**Interfaces:**
- Consumes: Task 1–6 全部交付。
- Produces: M0 关闭证据；M1 计划输入。

- [ ] **Step 1: 全量门禁**

Run: `node scripts/verify-miniapp-m0.js && npm run lint`
Expected: 7+ 项检查全部 missingCount=0，退出码 0；lint 绿。将 JSON 输出摘要（各项 total/missingCount）写入 M0 文档头部，标注「结构等价取证」。

- [ ] **Step 2: 规格计数更正 + docs/12 回写**

规格 §4.6 与 §7 M0 行「86 权限码覆盖核对」改「106 唯一权限码覆盖核对（86 v2 三态 + 20 旧码独有）」；docs/12 新增 H 组（小程序重构 M0—M6），M0 行标 ✅ 完成（2026-11-15），M1—M6 待办；版本修订表加行。

- [ ] **Step 3: 提交 + 用户审阅**

```bash
git add docs/12-未完成事项清单.md docs/superpowers/specs/2026-11-15-miniapp-redesign-design.md docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md
git commit -m "docs: M0 门禁全绿回写（docs/12 H 组+规格 106 码口径更正）"
```

呈交用户审阅 M0 映射文档；审阅通过后产出 M1 实施计划（writing-plans）。