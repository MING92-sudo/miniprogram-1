# 博威无纸化小程序重构 M3 维保作业域实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 落地 pkg-maintenance 9 页 + 急修 3 页（pages/order/*）+ 记录生命周期 UI；合规主干（签到/动态码/清单/签退/各签各的/离线上报/上报快照）逐字保留；删旧 order/*、fault/*、calendar。

**Architecture:** 合规页采用「迁移保逻辑 + 深色换肤」——旧页 JS 逻辑逐字迁入子包新路径（仅调 require 相对路径与 definePage 守卫包装），WXML 套 .bw + nav-bar 换肤，不重写校验/上报链路。task 页（屏 04）合并 detail+checklist+item-form 为单页三态（概览/点检/填写），复用既有 services/order 方法。急修七态 UI 接 M1 端点（grab/arrive/submit/confirm/cancel/evaluate）。生命周期 UI 接 M1 端点（signature-refuse/review/sign-request/archive/refuse-by-token）。

**Tech Stack:** 原生小程序 CommonJS / ES2018 / WXSS .bw 作用域 / M2 组件库 / services/order|fault|unit。

**Spec:** `docs/superpowers/specs/2026-11-15-miniapp-redesign-design.md` §4.1/§4.3/§5；M0 映射 §1/§3/§4。

## Global Constraints

- **合规主干逐字不动**（AGENTS §3.1–3.5）：签到 GPS 必填/缺失 422/Haversine 三级阈值/UNKNOWN 留痕回填/主配合分别留痕/重复 1003；动态码 HMAC 5s/60s/原子消费/校验先于写；清单周期项 notInThisRun 豁免/关键项 photoRequired≥1 照片 NA 除外/异常显式 S0–S7；各签各的 POST /work-orders/{id}/signature；仅主维保签退 PROCESSING/≥30 分钟双保险/双签齐；签退冻结快照+2.6 即报，失败仅标记逐条人工重报。**迁移只改路径/守卫/换肤，不改这些逻辑。**
- 记录生命周期叠加在签退之后（V42）；归档/修订/拒签不触发平台调用；不重报 2.6（D2/P-5）。
- 急修七态（order/*=Fault）；维保工单绝不套急修七态（§1.3 语义反转）。
- 页面只调 services；错误码/文案取 constants；时间 utils/util；照片/签名走 services/upload + common 页。
- 界面全中文；perm-button 双档；四态齐全； RouteMeta 白名单 + definePage。
- 每任务门禁：根 lint + verify-miniapp-routes + verify-m1-sync + verify-dispatch/templates（触及派单/模板）；mvn test 后端无改动不跑。
- 共享 worktree miniprogram-1-m2（codex/miniapp-redesign）；提交前 git status 核对，只 add 本任务路径，不碰 cloudbaserc.json（他人）。
- inline 执行 + 每任务自审留痕（.superpowers/sdd/2026-11-15-miniapp-redesign-m3/）。

## File Structure

| 文件 | 职责 | 来源 |
|---|---|---|
| `pkg-maintenance/pages/orders/index.*` | 我的维保工单列表（状态筛选） | 迁 pages/order/list |
| `pkg-maintenance/pages/task/index.*` | 作业执行（屏 04，概览/点检/填写三态） | 合并 order/detail+checklist+item-form |
| `pkg-maintenance/pages/checkin/index.*` | 签到（GPS+动态码内联） | 迁 order/checkin（逻辑逐字） |
| `pkg-maintenance/pages/dynamic-code/index.*` | 双人动态码 | 迁 order/dynamic-code（逻辑逐字） |
| `pkg-maintenance/pages/checkout/index.*` | 签退（双签状态+自检） | 迁 order/checkout（逻辑逐字） |
| `pkg-maintenance/pages/record-detail/index.*` | 记录单详情+生命周期 UI | 迁 order/record-preview + 生命周期 |
| `pkg-maintenance/pages/plan/index.*` | 维保计划月视图 | 迁 pages/calendar |
| `pkg-maintenance/pages/record-sign/index.*` | 维保双人签字（包装 signature） | 新建 |
| `pkg-maintenance/pages/record-archive/index.*` | 归档/PDF+哈希 | 新建 |
| `pages/order/list/index.*` | 报修接单池（屏 07，七态） | 迁 fault/list + 七态 |
| `pages/order/detail/index.*` | 急修单详情（屏 06，七态流转） | 迁 fault/detail + 七态 |
| `pages/order/report/index.*` | 一键报修+员工代报修 | 合并 fault/report+unit/report |
| `app.json` | +pkg-maintenance 子包 9 页 + 主包 order 3 页；删旧 order/* fault/* calendar | 改 |
| `utils/nav.js` | 删已迁路由的 TRANSITION_MAP 行 | 改 |
| `config/routes.js` | built:true 推进 M3 页 | 改 |
| `services/order.js`/`fault.js` | 已有 M1 方法，按需补 | 改 |
| `mock/server.js` | 七态/生命周期 mock 保真 | 改 |
| `docs/03`/`docs/12`/`docs/04` | 回写 | 改 |

**裁决（预检）**：
- R1：合规页迁移=JS 逐字复制 + require 路径调整 + definePage 包装 + WXML .bw/nav-bar 换肤；**校验/上报/动态码逻辑零改动**，自审逐行 diff 核对。
- R2：task 页三态合并复用 services/order 既有方法（getOrderDetail/getChecklist/submitChecklistItem/runItemThisTime），不新增端点；item-form 的实测/拍照/异常逻辑迁入 task 页填写态。
- R3：record-sign 包装 common/signature（navigateTo 传 as=primary|assist + orderId），签完回 record-detail；各签各的端点不变。
- R4：急修七态 UI 状态映射 M0 §3（OPEN→ASSIGNED→PROCESSING→PENDING_CONFIRM→CLOSED/REFUSED/CANCELLED）；到场打卡显式 arrive 优先。
- R5：删旧页同步删 app.json 注册 + nav TRANSITION_MAP 对应行 + routes built 推进；M0 守卫 routes-old 并集仍绿（旧页减少）。
- R6：生命周期 UI 仅展示状态 + 触发 M1 端点；record:review 在管理端执行，小程序仅展示（M0 澄清）。

---

### Task 1: pkg-maintenance 脚手架 + orders 列表 + plan 月视图

**Files:** Create pkg-maintenance/pages/orders/index.* + plan/index.*；Read pages/order/list.* + pages/calendar.*；Modify config/routes.js（built）+ app.json（子包注册暂缓 Task 6）

- [ ] Step 1: 读旧 order/list.js + calendar/index.js，迁逻辑入 orders/plan（definePage + .bw + nav-bar + custom-tabbar? orders 非 tab 宿主不挂 tabbar；plan 同）。orders 状态筛选 chips（PENDING/PROCESSING/DONE）+ 四态；plan 月视图日历格 + 当日工单。
- [ ] Step 2: lint + 守卫；自审逻辑逐字。
- [ ] Step 3: 提交 `feat: M3 维保工单列表 + 计划月视图迁子包`

### Task 2: 合规三页迁移（checkin/dynamic-code/checkout，逻辑逐字）

**Files:** Create pkg-maintenance/pages/{checkin,dynamic-code,checkout}/index.*；Read pages/order/{checkin,dynamic-code,checkout}.*

- [ ] Step 1: checkin 迁移——JS 逐字（GPS 必填/Haversine/UNKNOWN 回填/主配合留痕/重复 1003/动态码内联），require 路径 ../../.. 调整，definePage 包装；WXML .bw+nav-bar 换肤，保留所有 wx:if 校验提示。
- [ ] Step 2: dynamic-code 迁移——HMAC 5s 刷新/60s 有效/verify 不消费逻辑逐字。
- [ ] Step 3: checkout 迁移——PROCESSING/≥30 分钟双保险/双签齐/仅主维保/冻结快照+2.6 即报逻辑逐字；签退页签字状态展示（各签各的在 record-sign）。
- [ ] Step 4: lint + verify-dispatch + 守卫；**自审逐行 diff 旧→新 JS，确认合规逻辑零改动**。
- [ ] Step 5: 提交 `feat: M3 签到/动态码/签退迁子包（合规主干逐字保留）`

### Task 3: task 作业页（屏 04 三态）+ record-detail（生命周期 UI）

**Files:** Create pkg-maintenance/pages/task/index.* + record-detail/index.*；Read pages/order/{detail,checklist,item-form,record-preview}.*

- [ ] Step 1: task 页三态——概览（工单信息+签到/签退入口+进度）、点检（清单按附件 A—D 分组，真实模板，周期项 notInThisRun 豁免，关键项 photoRequired）、填写（实测值/拍照/异常 S0–S7 强制备注）；复用 services/order 方法；点检项四态（待检/进行中/合格/异常）。
- [ ] Step 2: record-detail——迁 record-preview 展示逻辑 + 生命周期状态条（PENDING_REVIEW/PENDING_CLIENT_SIGN/PENDING_CLIENT_SIGNATURE/SIGNED/ARCHIVED/REFUSED_RETURNED）+ 动作按钮（发起签署 sign-request/配合退回 signature-refuse/归档投影 archive，perm-button 门控）。
- [ ] Step 3: lint + 守卫；自审清单/异常逻辑保留。
- [ ] Step 4: 提交 `feat: M3 作业执行页（屏04 三态）+ 记录单详情生命周期 UI`

### Task 4: record-sign 双人签字 + record-archive 归档

**Files:** Create pkg-maintenance/pages/record-sign/index.* + record-archive/index.*；Read pages/common/signature.*

- [ ] Step 1: record-sign——包装 common/signature（navigateTo 传 orderId+as），主检/配合各签各的（POST signature），双签状态展示，版本号/哈希核对（屏 05）；同账号主配合拒绝。
- [ ] Step 2: record-archive——GET archive 投影（PDF url+SHA-256+archivedAt），非 ARCHIVED 422 态；导出 perm-button record:export。
- [ ] Step 3: lint + 守卫；提交 `feat: M3 维保双人签字页 + 归档 PDF/哈希页`

### Task 5: 急修三页（pages/order/*，七态）

**Files:** Create pages/order/{list,detail,report}/index.*；Read pages/fault/{list,detail,report}.* + pages/unit/report.*

- [ ] Step 1: order/list（屏 07 接单池）——迁 fault/list + 七态筛选 chips + 抢单 grab（order:grab 本区域）+ 急修优先排序 + 四态双分支。
- [ ] Step 2: order/detail（屏 06）——迁 fault/detail + 七态流转按钮（arrive GPS 打卡/submit 原因措施必填/confirm CLIENT_SIGNER/cancel 原因必填/evaluate）+ SLA 倒计时 + timeline。
- [ ] Step 3: order/report——合并 fault/report（员工）+ unit/report（CLIENT 一键报修），电梯扫码回填 + 故障描述≥5 字 + 照片。
- [ ] Step 4: lint + 守卫；提交 `feat: M3 急修接单池/详情/报修三页（七态流转）`

### Task 6: 子包注册 + 删旧页 + 收口

**Files:** Modify app.json（+pkg-maintenance 9 + order 3，删旧 order/* fault/* calendar）+ utils/nav.js（删已迁映射）+ config/routes.js（built）+ mock + docs/03/12/04

- [ ] Step 1: app.json 注册子包 pkg-maintenance（preloadRule maint/home→pkg-maintenance）+ 主包 order 3 页；删旧 order/{list,detail,checkin,dynamic-code,checklist,item-form,checkout,record-preview}、fault/{list,detail,report}、calendar/index 注册与文件。
- [ ] Step 2: nav.js 删已迁路由 TRANSITION_MAP 行；routes built 推进；M0 映射 §4 批次状态更新。
- [ ] Step 3: 全门禁（routes 10/10、M0 11/11、m1-sync、dispatch/templates、lint）；mock 七态/生命周期冒烟。
- [ ] Step 4: docs/03（页面结构）/docs/12 H 组 M3 ✅/docs/04（若有端点变更）回写。
- [ ] Step 5: 提交 `feat: M3 收口（子包注册/删旧维保急修页/门禁回写）`

## Self-Review

- spec §4.1 合规主干：Task 2/3 逐字保留（R1 diff 自审）；§4.3 急修七态 Task 5；生命周期 Task 3/4。✔
- 删旧页：Task 6 删 order/* fault/* calendar（spec M3 行）；handover/filing/unit 留 M4/M5。✔
- 风险：task 页三态合并是最高风险点（清单/异常/拍照逻辑），Task 3 自审重点核对 item-form 逻辑迁入完整性。