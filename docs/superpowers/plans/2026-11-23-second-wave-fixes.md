# 第二波五项修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依次修复离线属主、多角色 UI、弹窗残值、时间边界和移交缺件死入口，并分别提交。

**Architecture:** 每项在最新 master 建独立 worktree/分支，先失败后最小修复。小程序逻辑用现有 Node 测试脚本，管理端用 Vitest；每项独立提交，不合 master。

**Tech Stack:** CommonJS、微信小程序、Vue 3、Vitest、Pinia、Node.js。

**Spec:** `docs/superpowers/specs/2026-11-23-second-wave-fixes-design.md`

## Global Constraints

- 不改 constants/index.js 或第四阶段独占文件；不扩范围。
- 每项先 RED 后 GREEN，聚焦测试后跑相关回归。
- 每项本地提交仅含代码/测试，docs 不提交，不合 master。

## Review Focus

- 上传期间换号不得让 A 的业务请求使用 B 会话；未登录入队必须有明确提示。
- 次要角色能力在四个 UI 上应与权限并集一致，后端仍强校验。
- 切换工单后旧表单值不得进入新单请求。
- 无参数时间兼容不得被破坏，非法日历日期不得静默归一。
- 缺件不得生效的后端规则不变，页面不得再宣称容缺。

---

### Task 1: 离线队列属主与上传换号

**Files:**
- Modify: `utils/offline.js`
- Test: `scripts/test-offline-queue.js`

**Interfaces:**
- Consumes: 既有 `getAccountId()`、任务 ownerId、上传循环和 request 幂等键。
- Produces: 未登录 `enqueue` 不入队并 toast 提示；每个上传 await 后及 request 前复核 owner；失败保留任务。

- [ ] **Step 1: 写失败测试**

在 `scripts/test-offline-queue.js` 覆盖：未登录入队被拒绝且有提示；上传中 A→B 后不得发请求且 A 任务保留；A/B 各有任务时 B 只 flush 自己、A 保留。

- [ ] **Step 2: 验证 RED**

Run: `node scripts/test-offline-queue.js`

Expected: 新增场景 FAIL，不换既有合法断言。

- [ ] **Step 3: 最小修复**

`enqueue` 无 owner 时直接 toast 并返回原队列长度；在上传循环内 await 后和 request 前用同一 owner 校验，失败走既有 catch/persist，不删除任务。

- [ ] **Step 4: 验证 GREEN 与 lint**

Run: `node scripts/test-offline-queue.js && npx eslint --no-ignore utils/offline.js scripts/test-offline-queue.js`

Expected: 全部 PASS。

- [ ] **Step 5: 提交**

仅 add 两文件，提交信息：`修复离线任务属主隔离与换号补传`。不合 master。

---

### Task 2: 多角色 UI 使用权限并集

**Files:**
- Modify: `admin/src/views/SettingsView.vue`
- Modify: `admin/src/views/RolesView.vue`
- Modify: `admin/src/views/ProjectsView.vue`
- Modify: `admin/src/ui/PageMeta.vue`
- Create: `admin/src/ui/multi-role-ui.test.js`

**Interfaces:**
- Consumes: `perspective.roleSet`、`stateForRoles(roles, code)`。
- Produces: 四处权限判定与角色展示使用角色集并集；activeRole 仍用于需要单角色的既有 scope 展示。

- [ ] **Step 1: 写失败测试**

新增挂载测试：主角色无权限、次要角色分别具有 config/invite/contract 能力时，对应 Settings/Roles/Projects 入口可见，PageMeta 显示角色集且权限为并集结果；单角色行为不变。

- [ ] **Step 2: 验证 RED**

Run: `npm --prefix admin test -- src/ui/multi-role-ui.test.js`

Expected: 旧 activeRole 判定导致 FAIL。

- [ ] **Step 3: 最小修复**

三处视图 `allow` 改用 `stateForRoles(perspective.roleSet, code)`；PageMeta 权限值同样改并集，角色文本展示 roleSet 标签。不改权限矩阵。

- [ ] **Step 4: 验证 GREEN 与全量 admin**

Run: `npm --prefix admin test && npm --prefix admin run lint`

Expected: 全部 PASS。

- [ ] **Step 5: 提交**

仅 add 五个文件，提交信息：`修复多角色管理端权限并集展示`。不合 master。

---

### Task 3: 工单切换清空弹窗表单

**Files:**
- Modify: `admin/src/views/OrderDetailView.vue`
- Test: `admin/src/views/OrderDetailView.test.js`

**Interfaces:**
- Consumes: 既有 `resetActions` 和 dispatch/close/cancel/proxy 响应式表单。
- Produces: 切换路由时关闭弹窗、清错误并恢复表单默认值。

- [ ] **Step 1: 写失败测试**

新增测试：在 A 填四类表单后切到 B，再打开对应弹窗，断言输入为空、proxySource 恢复 PHONE，且不会携带 A 的值提交 B。

- [ ] **Step 2: 验证 RED**

Run: `npm --prefix admin test -- src/views/OrderDetailView.test.js`

Expected: 旧 resetActions 不清值导致 FAIL。

- [ ] **Step 3: 最小修复**

仅在 `resetActions` 中重置四个表单的值；不改其他路由/加载逻辑。

- [ ] **Step 4: 验证 GREEN 与回归**

Run: `npm --prefix admin test -- src/views/OrderDetailView.test.js && npm --prefix admin run lint`

Expected: 全部 PASS。

- [ ] **Step 5: 提交**

仅 add 两文件，提交信息：`清除工单切换后的弹窗表单残值`。不合 master。

---

### Task 4: 时间工具边界 fail-closed

**Files:**
- Modify: `utils/util.js`
- Test: `scripts/test-timezone.js`

**Interfaces:**
- Consumes: 既有 `formatTime`/`parseTime` GMT+8 规则。
- Produces: 无参数/undefined 保持当前时间；`parseTime('')=0` 保留；显式 null/空串/0/非法日历日期返回空或 NaN，不归一为有效时间。

- [ ] **Step 1: 写失败测试**

在现有 UTC/Shanghai 双时区测试中增加：`formatTime(0)`、`formatTime('')`、`formatTime(null)` 返回 `''`；`parseTime('2026-02-31')` 为 NaN；合法时间输出与时间戳完全不变；无参数仍为当前时间。

- [ ] **Step 2: 验证 RED**

Run: `node scripts/test-timezone.js`

Expected: 新边界断言 FAIL。

- [ ] **Step 3: 最小修复**

区分无参数与显式空值；校验 DATE_TIME_RE 捕获的日历日期和时分秒范围，非法返回空/NaN。其他合法输入与 parseTime 空值兼容不变。

- [ ] **Step 4: 验证 GREEN 与 lint**

Run: `node scripts/test-timezone.js && npx eslint --no-ignore utils/util.js scripts/test-timezone.js`

Expected: 全部 PASS。

- [ ] **Step 5: 提交**

仅 add 两文件，提交信息：`修复时间工具空值与非法日期边界`。不合 master。

---

### Task 5: 移除移交容缺死入口

**Files:**
- Modify: `pkg-handover/pages/upload/index.wxml`
- Modify: `pkg-handover/pages/upload/index.js`
- Test: `scripts/test-handover-rectify-contract.js`

**Interfaces:**
- Consumes: 后端 `submitDocs` 三文件强制契约和现有上传页测试。
- Produces: 页面仅允许上传/替换三份强制资料，不再展示标记缺件或容缺文案；提交仍三字段齐全才发请求。

- [ ] **Step 1: 写失败测试**

在现有契约脚本读取上传 WXML，断言不包含「标记缺件」「容缺受理」且包含「三项强制资料上传齐全后才可提交」；保留缺件零请求与齐全单请求行为。

- [ ] **Step 2: 验证 RED**

Run: `node scripts/test-handover-rectify-contract.js`

Expected: 旧容缺文案/按钮存在导致 FAIL。

- [ ] **Step 3: 最小修复**

移除 missing 状态、切换方法和提交条件，WXML 只保留上传/替换并更新强制文案；不改变后端或 mock 契约。

- [ ] **Step 4: 验证 GREEN 与回归**

Run: `node scripts/test-handover-rectify-contract.js && npx eslint --no-ignore pkg-handover/pages/upload/index.js scripts/test-handover-rectify-contract.js`

Expected: 全部 PASS。

- [ ] **Step 5: 提交**

仅 add 三文件，提交信息：`移除移交资料容缺死入口`。不合 master。
