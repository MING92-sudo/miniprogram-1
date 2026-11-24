# 客户签字归档与签退监管未知结果恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 签退上报未知结果停留在可人工核对状态（杜绝 SUBMITTED 卡死被 2.8 自动重报），管理端逐条人工核对恢复；客户签字与归档解耦，签字成功不被归档失败拖垮。

**Architecture:** 上报链路加 `REPORTING` 抢占态 + `reconcile` 逐条人工核对端点（纯本地条件更新，不触平台）；签字链路 `signRecord` 归档异常内化 + `archive()` 幂等可重入。

**Tech Stack:** Java 17 / Spring Boot 3.3.4 / MyBatis-Plus / JUnit 5 / Mockito；微信小程序原生；Vue3 + 自建组件库；Node 断言脚本。

**Spec:** `docs/superpowers/specs/2026-11-23-signoff-report-recovery-design.md`

## Global Constraints

- 禁止对 `originalRecordId` 的任何自动/批量/定时重试；reconcile 不得调用 `PlatformClient` 任何方法。
- 监管 HTTP / PDF / 存储不纳入数据库长事务；`ARCHIVED` 必经 `SIGNED`（归档前置断言 `confirmStatus=CONFIRMED && lifecycleStatus=SIGNED`）。
- 不触发真实平台写接口（2.3/2.4/2.6/2.8）；测试一律故障注入/mock 平台客户端。
- 零 Flyway 迁移（`report_status` VARCHAR 无枚举约束）；`SUBMITTED` 语义（未配置平台的待迁移）与 `syncLegacy` 扫描条件保持不变。
- rework（`DRAFT`）口径不动：复核退回整改新版本保留原 `originalRecordId` 且绝不上报。
- 接口两处同步：`services/` + `mock/server.js`；文案只从 `constants/index.js` 取；admin 不引新 UI 框架。
- 后端改业务规则必须补同包单测；提交信息一行中文、动词开头；`docs/superpowers/` 不入提交。
- 不提交代码，除非后续用户明确选择合并/PR（worktree 分支 `codex/signoff-report-recovery`，合并由协调会话统一收口）。

## Review Focus

- 并发签退同一工单：`completeCheckout` 条件更新先拦截，`beginUpload` 0 行 → 1003 是双保险——Task 1 步骤测试必须覆盖 beginUpload 返回 0 时 `platformClient` 零调用。
- 权限缺口修复（协调终审修正案，用户已拍板）：`POST /platform/records/{id}/reupload` 与 reconcile 同补 `@RequirePerm(PermissionMatrix.REPORT_RETRY_NEW)`——Task 2 Step 1 用例 8（反射断言两端点注解）与 Step 3 落地；reviewer 复核矩阵授予面（PA/MM）与 admin 端 `PermButton code="report:retry"` 一致。
- 历史无 `workOrderId` 的记录 reconcile 后工单标签不更新（设计裁定 1 的接受项）——reconcile 测试需断言无 `workOrderId` 时不报错、不调用 `orderMapper`。
- mock 自然种子数据不含 `REPORTING/UNKNOWN` 停留态，verify 脚本必须用 `withRecord` 注入构造；人工走查清单需提示。
- `syncLegacy()` 返回类型 `int` → `SyncLegacyResult` 是签名变更：全仓唯一调用方是 `PlatformSyncService.syncAll`（已 grep 确认），reviewer 复核无第二个调用方。

---

### Task 1: 上报状态机（REPORTING 抢占 + 停留态收口 + 工单标签同步）

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/mapper/MaintainRecordMapper.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/PlatformReportService.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/PlatformSyncService.java`（`syncAll` 增加 `legacyStuck`）
- Test: `backend/src/test/java/com/cqwlw/maintenance/PlatformReportServiceTest.java`

**Interfaces:**
- Consumes: 既有 `updateReportProgress/beginReupload/completeReupload/beginLegacyUpload/completeLegacyUpload`、`WorkOrderMapper.updateReportStatus(String id, String reportStatus)`（已存在，WorkOrderMapper.java:48-49）。
- Produces:
  - `MaintainRecordMapper.beginUpload(String id)` → `int`：`UPDATE maintain_record SET report_status='REPORTING' WHERE id=#{id} AND report_status='SUBMITTED'`。
  - `PlatformReportService.syncLegacy()` → `SyncLegacyResult`（服务内 `record SyncLegacyResult(int uploaded, int stuck)`）；**签名变更**，唯一调用方 `PlatformSyncService.syncAll`。
  - `attemptUpload(MaintainRecord)` 返回值域扩为 `SUBMITTED/REPORTING/REPORTED/FAILED/UNKNOWN`；`OutcomeUnknownException` 类删除，不再有任何路径外抛。
  - Task 2 依赖：`syncOrderReportStatus(MaintainRecord r)`（private，记录带 `workOrderId` 时同步工单状态，失败仅 warn）、`hasNextVersion(MaintainRecord)`（既有 private，Task 2 复用同类逻辑）。

- [ ] **Step 1: 写失败测试（6 个用例，加入既有 `PlatformReportServiceTest`）**

1. `attemptUploadClaimsReportingBeforePlatformCall`：configured，`beginUpload` 返回 1，断言 `platformClient.uploadMaintenanceRecord` 调用时记录已是 `REPORTING`（用 `InOrder` 或 answer 回调读 `r.reportStatus`）。
2. `attemptUploadRejectsWhenClaimFails`：`beginUpload` 返回 0 → `BizException(1003)`，`platformClient` 零交互。
3. `attemptUploadStaysReportingWhenBothLocalWritesFail`：平台成功，`updateReportProgress(REPORTED,...)` 与 `updateReportProgress(UNKNOWN,...)` 均 `thenThrow` → 返回 `"REPORTING"`、不抛异常、`r.reportStatus="REPORTING"`。
4. `attemptUploadReturnsUnknownWhenFallbackWriteSucceeds`：REPORTED 写抛、UNKNOWN 写成功 → 返回 `"UNKNOWN"`。
5. `syncLegacySkipsStuckAndContinues`：两条 SUBMITTED 记录，第一条平台成功后两次本地写均失败（停留 `LEGACY_UPLOADING`），第二条成功 → 返回 `uploaded=1, stuck=1`；verify 第二条也调用了 `platformClient.uploadLegacyRecord`（旧实现会中断）。
6. `reuploadSyncsWorkOrderStatus`：FAILED 链末端记录带 `workOrderId="wo_1"`，reupload 成功 → verify `orderMapper.updateReportStatus("wo_1","REPORTED")`；另断言 `upload` 内两次本地写失败时 reupload 不抛、记录停留 `REUPLOADING`。

测试脚手架沿用既有 `record(...)` helper 与 Mockito mock（`recordMapper/logMapper/platformClient/tokenService/props`）；新增 `WorkOrderMapper` mock 注入构造器。

- [ ] **Step 2: 验证失败**

Run: `mvn -f backend/pom.xml "-Dtest=PlatformReportServiceTest" test`
Expected: 用例 3/5/6 失败（旧实现抛 OutcomeUnknownException / syncLegacy 中断 / 无 orderMapper 交互）；用例 1/2/4 按现状可过或失败均可（1/2 是新行为断言，旧实现无 beginUpload 会编译错——属预期，编译错误即"失败"）。

- [ ] **Step 3: 实施**

- `MaintainRecordMapper` 加 `beginUpload`（SQL 见 Produces）。
- `PlatformReportService` 构造器注入 `WorkOrderMapper orderMapper`。
- `attemptUpload`：configured 时先 `beginUpload`（0 → `BizException(1003, "该记录正在上报或状态已变化，请刷新后查看")`），置 `r.reportStatus="REPORTING"` 再走原 upload 捕获逻辑。
- `markUnknown`：`updateReportProgress(UNKNOWN,...)` 失败时不再抛 `OutcomeUnknownException`，改为返回当前 `r.reportStatus`（调用方已置为在途态：REPORTING/REUPLOADING/LEGACY_UPLOADING，DB 停留该态）；删除 `OutcomeUnknownException` 类及全部 catch 分支。
- `reupload`/`syncLegacy` 中 `catch (OutcomeUnknownException e)` 分支删除；`syncLegacy` 对非 REPORTED 结果 `stuck++` 后继续循环；返回类型改 `SyncLegacyResult`。
- `reupload` 成功/失败后、`attemptUpload` 末尾（checkout 已自行回写工单状态处除外）调用 `syncOrderReportStatus(r)`：`workOrderId` 非空白才 `orderMapper.updateReportStatus`，异常仅 `log.warn`。
- `PlatformSyncService.syncAll`：`legacyUploaded` 取 `result.uploaded()`，新增 `summary.put("legacyStuck", result.stuck())`。

- [ ] **Step 4: 验证聚焦测试**

Run: 同 Step 2。
Expected: 全部 PASS（含既有用例回归：未配置平台仍返回 SUBMITTED、FAILED 路径、REUPLOADING/LEGACY_UPLOADING 抢占语义）。

- [ ] **Step 5: 全量 backend 回归**

Run: `mvn -f backend/pom.xml test`
Expected: 全部 PASS。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/mapper/MaintainRecordMapper.java backend/src/main/java/com/cqwlw/maintenance/service/PlatformReportService.java backend/src/main/java/com/cqwlw/maintenance/service/PlatformSyncService.java backend/src/test/java/com/cqwlw/maintenance/PlatformReportServiceTest.java
git commit -m "修复签退上报未知结果卡死并引入上报中抢占态"
```

---

### Task 2: reconcile 逐条人工核对端点

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/mapper/MaintainRecordMapper.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/PlatformReportService.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/PlatformController.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/PlatformReportServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `syncOrderReportStatus`、`hasNextVersion` 同类链末端判断、`log(...)` 审计 helper（既有 private）；`IdempotencyService.executeDb`（既有）；`PermissionMatrix.REPORT_RETRY_NEW`（`"report:retry"`，已授予 PA/MM）。
- Produces:
  - `MaintainRecordMapper.reconcileReport(String id, String outcome)` → `int`：`UPDATE maintain_record SET report_status=#{outcome} WHERE id=#{id} AND report_status IN ('REPORTING','REUPLOADING','LEGACY_UPLOADING','UNKNOWN')`。
  - `PlatformReportService.reconcile(String id, String outcome, String note)` → `MaintainRecord`（状态已置 outcome）。
  - `POST /platform/records/{id}/reconcile`，body `{outcome, note}` → `{ok, id, reportStatus}`；Task 6 admin 端依赖此契约。

- [ ] **Step 1: 写失败测试（7 个用例）**

1. `reconcileRejectsInvalidOutcome`：outcome=`"FOO"` → `BizException(422)`，mapper 零交互。
2. `reconcileRejectsNonStuckStatus`：记录 `REPORTED` → 1003「当前状态无需核对」。
3. `reconcileRejectsSupersededRecord`：`hasNextVersion` 为真 → 1003「记录已有后继版本」。
4. `reconcileRejectsConcurrentChange`：`reconcileReport` 返回 0 → 1003「状态已变化，请刷新后查看」。
5. `reconcileMarksReportedWithAuditAndOrderSync`：`REPORTING` 记录带 `workOrderId` → 返回 `REPORTED`；verify `logMapper.insert` 的 `RegUploadLog.action="RECONCILE"`、`status="SUCCESS"`、`platformMessage` 含 note；verify `orderMapper.updateReportStatus(workOrderId,"REPORTED")`。
6. `reconcileMarksFailedAllowsManualReupload`：`UNKNOWN` → `FAILED`；`RegUploadLog.status="MANUAL_FAILED"`。
7. `reconcileSkipsOrderSyncWhenNoWorkOrderId`：`workOrderId=null` → 正常完成，`orderMapper` 零交互。
8. `platformWriteEndpointsRequireReportRetryPerm`（新建 `PlatformControllerPermTest` 或并入同包鉴权测试）：反射断言 `PlatformController.reupload` 与 `reconcile` 方法均带 `@RequirePerm`，且 `value` 等于 `PermissionMatrix.REPORT_RETRY_NEW`。

note 含手机号时断言日志 `platformMessage` 已打码（复用既有 `mask`，用例 5 中断言不含完整 11 位手机号）。

- [ ] **Step 2: 验证失败**

Run: `mvn -f backend/pom.xml "-Dtest=PlatformReportServiceTest" test`
Expected: 编译错误（方法不存在）即失败。

- [ ] **Step 3: 实施**

- mapper 加 `reconcileReport`（SQL 见 Produces）。
- `reconcile(...)`：outcome 白名单 `{REPORTED, FAILED}` 否则 422；`selectById` 空 → 1404；状态不在四态集合 → 1003「当前状态无需核对（仅上报中/结果待核对的记录可核对）」；链末端校验；`reconcileReport` 0 行 → 1003；置 `r.reportStatus`；写 `reg_upload_log`（`action="RECONCILE"`，`status= SUCCESS|MANUAL_FAILED`，`platformMessage=mask(note)` 经既有 log() 截断）；`syncOrderReportStatus(r)`。**不得调用 `platformClient`**——实现后 grep 自证。
- `PlatformController`：`@PostMapping("/platform/records/{id}/reconcile")` + `@RequirePerm(PermissionMatrix.REPORT_RETRY_NEW)`；注入 `IdempotencyService`；`executeDb(idemKey, safeBody, request, () -> reportService.reconcile(id, outcome, note))`；返回 `{ok:true, id, reportStatus}`；outcome/note 从 body 取值（缺 note 用空串）。
- 同一修正案：`reupload` 方法补 `@RequirePerm(PermissionMatrix.REPORT_RETRY_NEW)`（既有缺口，与 reconcile 同权限点）。

- [ ] **Step 4: 验证聚焦测试 + 全量回归**

Run: `mvn -f backend/pom.xml "-Dtest=PlatformReportServiceTest" test` 然后 `mvn -f backend/pom.xml test`
Expected: 全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/mapper/MaintainRecordMapper.java backend/src/main/java/com/cqwlw/maintenance/service/PlatformReportService.java backend/src/main/java/com/cqwlw/maintenance/controller/PlatformController.java backend/src/test/java/com/cqwlw/maintenance/PlatformReportServiceTest.java
git commit -m "新增上报记录逐条人工核对端点"
```

---

### Task 3: 签字/归档解耦与归档幂等

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/UnitRecordService.java`（`signRecord`，约 137-184 行）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/RecordLifecycleService.java`（`archive`，约 288-307 行）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/FileStorageService.java`（新增 `deleteFile`）
- Test: `backend/src/test/java/com/cqwlw/maintenance/UnitRecordServiceTest.java`（不存在则新建，仿同包测试风格）
- Test: `backend/src/test/java/com/cqwlw/maintenance/RecordLifecycleServiceTest.java`（同上）

**Interfaces:**
- Consumes: `FileStorageService.storeBytes(...)`（既有）；`RecordLifecycleService.onClientSigned`（既有）。
- Produces:
  - `FileStorageService.deleteFile(String fileId)` → `void`：best-effort 删 COS 对象 + 本地缓存 + `file_record` 行，全程异常只 warn（不抛）。
  - `signRecord` 响应由 `{ok:true}` 扩为 `{ok:true, archived:boolean}`；Task 4 mock 契约与 Task 5 依赖此键。
  - `archive(MaintainRecord)` 幂等：`ARCHIVED` 且 `archiveFileId` 非空直接返回；条件更新落败方删除**自己刚存的**文件。

- [ ] **Step 1: 写失败测试（5 个用例）**

1. `signSucceedsWhenArchiveFails`：`onClientSigned` 抛 `RuntimeException`（PDF/存储故障注入）→ 响应 `{ok:true, archived:false}`；记录库内 `lifecycleStatus=SIGNED`、`confirmStatus=CONFIRMED`（签字不回滚）；仅 warn 日志。
2. `signReportsArchivedTrueWhenArchiveSucceeds`：归档正常 → `{ok:true, archived:true}`。
3. `archiveRejectsUnsignedRecord`：`lifecycleStatus=PENDING_CLIENT_SIGNATURE` → `BizException(422)`，`storeBytes` 零调用。
4. `archiveShortCircuitsWhenAlreadyArchived`：`ARCHIVED` + `archiveFileId` 非空 → 直接返回，`storeBytes`/`update` 零调用。
5. `archiveLoserDeletesOwnStoredFile`：条件更新返回 0 行（并发落败）→ verify `fileStorage.deleteFile(刚存入的 f.id)`；且**不得**删除库中 winner 的 `archiveFileId`（断言 deleteFile 参数等于本次 storeBytes 返回值）。

- [ ] **Step 2: 验证失败**

Run: `mvn -f backend/pom.xml "-Dtest=UnitRecordServiceTest,RecordLifecycleServiceTest" test`
Expected: 用例 1/3/5 失败（旧实现归档异常外抛、无前置断言、无 deleteFile）；2/4 按现状可能通过（属回归钉死）。

- [ ] **Step 3: 实施**

- `FileStorageService.deleteFile`：查 `AppFile`；`cosClient` 非空则 `deleteObject`（catch warn）；`Files.deleteIfExists(localPath(f))`（catch warn）；`fileMapper.deleteById`（catch warn）。
- `RecordLifecycleService.archive`：开头短路（`ARCHIVED && archiveFileId 非空 → return`）；断言 `confirmStatus="CONFIRMED" && lifecycleStatus="SIGNED"` 否则 422「记录未签署完成，不能归档」；`storeBytes` 后改条件更新（`eq(id).eq(lifecycleStatus,'SIGNED')` set archive 三件套 + `ARCHIVED`），0 行 → `deleteFile(本次 f.id)` + warn「并发归档落败，已清理自存文件」；非 0 行走原内存回写。
- `UnitRecordService.signRecord`：`onClientSigned` 包 try/catch（`Exception`），catch 内 `log.warn("签字成功但归档失败，记录留待归档: id={}, {}", r.id, e.getMessage())` 且 `archived=false`；返回 `JsonUtil.map("ok", true, "archived", archived)`。

- [ ] **Step 4: 验证聚焦测试 + 全量回归**

Run: 聚焦两测试类，然后 `mvn -f backend/pom.xml test`
Expected: 全部 PASS（含 `batchConfirmAs` 既有回归——归档异常内化后批量不再中断）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/service/UnitRecordService.java backend/src/main/java/com/cqwlw/maintenance/service/RecordLifecycleService.java backend/src/main/java/com/cqwlw/maintenance/service/FileStorageService.java backend/src/test/java/com/cqwlw/maintenance/UnitRecordServiceTest.java backend/src/test/java/com/cqwlw/maintenance/RecordLifecycleServiceTest.java
git commit -m "解耦客户签字与归档并实现归档幂等重入"
```

---

### Task 4: uploadStatus 映射 + mock 契约 + verify 脚本

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/UnitRecordService.java`（`uploadStatus`，594-606 行）
- Modify: `mock/data.js`（`uploadStatus` 镜像 587-588 行；新增 `reconcileRecord`）
- Modify: `mock/server.js`（新增 reconcile 路由；confirm 路由响应加 `archived`）
- Create: `scripts/verify-report-recovery.js`
- Modify: `.github/workflows/backend-build.yml`（verify 步骤加一行）
- Test: `backend/src/test/java/com/cqwlw/maintenance/UnitRecordServiceTest.java`

**Interfaces:**
- Consumes: Task 2 的 reconcile 契约（`{outcome,note}` → `{ok,id,reportStatus}`）；Task 3 的 `{ok,archived}`；`scripts/verify-p1-mock.js` 的 `req/expectCode/check/withRecord` 模式。
- Produces:
  - `uploadStatus` 映射：`REPORTING/REUPLOADING/LEGACY_UPLOADING/UNKNOWN → UNKNOWN`（后端与 mock 镜像一致）；Task 5 小程序展示依赖。
  - mock `POST /platform/records/:id/reconcile` 路由（Task 6 admin 本地联调用）。
  - `scripts/verify-report-recovery.js` 全绿是 Task 5 完成的验收条件之一。

- [ ] **Step 1: 写失败校验 `scripts/verify-report-recovery.js`**

仿 `verify-p1-mock.js` 头部（wx 桩 + `mockRequest` + `mock/data.js`），断言：
1. `reconcile` `REPORTING→REPORTED`：返回 `{ok:true, reportStatus:'REPORTED'}`；带工单锚点的记录工单 `reportStatus` 同步为 `REPORTED`（读 `d.db.orders` 断言）。
2. `reconcile` 非法 outcome（`'FOO'`）→ 422。
3. `reconcile` `FAILED` 状态记录 → 1003（FAILED 不属待核对集合）。
4. `reconcile` `UNKNOWN→FAILED` 后，同记录 `reupload` 返回 `{ok:true, reportStatus:'REPORTED'}`（契约串联）。
5. `/admin/records?reportStatus=REPORTING,UNKNOWN` 能按逗号多值过滤出注入的停留态记录（既有过滤语义回归）。
6. 注入记录的 `uploadStatus` 投影：`REPORTING/REUPLOADING/LEGACY_UPLOADING` → `'UNKNOWN'`（经 `/admin/records` 行断言）。
7. 客户签字 `POST /unit/records/:id/confirm` 响应含 `archived` 键（mock 归档恒成功 → `archived:true`）。
8. `constants/index.js` 含 `REPORT_STATUS_TEXT.UNKNOWN` 与 `REPORT_UNKNOWN_HINT`（require 断言，Task 5 落地后转绿）。

- [ ] **Step 2: 跑脚本确认失败**

Run: `node scripts/verify-report-recovery.js`
Expected: 断言 1/4/7 失败（reconcile 路由与 archived 键不存在）。

- [ ] **Step 3: 后端 uploadStatus 映射（先写失败单测）**

`UnitRecordServiceTest` 加 `uploadStatusMapsInflightStatesToUnknown`：四态 → `'UNKNOWN'`。跑红后改 `uploadStatus`：`REPORTING/REUPLOADING/LEGACY_UPLOADING` 返回 `"UNKNOWN"`（注释放行：在途/停留态统一待核对口径，不得落 PENDING 被当成待上报重试）。

- [ ] **Step 4: mock 对齐**

- `mock/data.js`：`uploadStatus` 镜像同改；新增 `reconcileRecord(id, outcome)`：状态不在四态集合 → `{code:1003,...}`；置 `reportStatus=outcome`；记录带 `workOrderId` 且订单存在 → 同步 `order.reportStatus`；返回记录（仿 `reuploadRecord` 597-604 行风格）。`module.exports` 补 `reconcileRecord`。
- `mock/server.js`：`reupload` 路由（1069-1073 行）后加 reconcile 路由：outcome 非 `REPORTED/FAILED` → 422；`d.reconcileRecord`；返回 `{ok:true, id, reportStatus}`。
- `mock/server.js` confirm / confirm-by-token 路由响应加 `archived: true`（mock 无归档失败路径，注释一行说明）。

- [ ] **Step 5: 跑脚本确认 1-7 绿**

Run: `node scripts/verify-report-recovery.js`
Expected: 断言 1-7 PASS；断言 8 此时仍 FAIL（Task 5 落地）——本任务以 1-7 绿为完成门槛，脚本汇总退出码在 Task 5 前允许非零，Task 5 步骤复跑必须全绿。

- [ ] **Step 6: CI 行移交 Task 5（执行期裁决）**

verify 脚本在本任务完成时断言 8 仍红（退出码非零），挂进 CI 会打红流水线；CI 加行改在 Task 5 verify 全 8 绿后随其提交（台账 pre-flight Ruling）。

- [ ] **Step 7: backend 回归 + Commit**

Run: `mvn -f backend/pom.xml "-Dtest=UnitRecordServiceTest" test`
Expected: PASS。

```bash
git add backend/src/main/java/com/cqwlw/maintenance/service/UnitRecordService.java backend/src/test/java/com/cqwlw/maintenance/UnitRecordServiceTest.java mock/data.js mock/server.js scripts/verify-report-recovery.js
git commit -m "对齐上报停留态映射与核对接口的 mock 契约"
```

---

### Task 5: 小程序待核对展示

**Files:**
- Modify: `constants/index.js`（`REPORT_STATUS_TEXT` 扩键 + 新增 `REPORT_UNKNOWN_HINT`）
- Modify: `pkg-maintenance/pages/orders/index.js`（reportTag 分支，34-35 行）
- Modify: `pkg-maintenance/pages/checkout/index.js`（签退响应停留态提示，182-189 行）
- Modify: `.github/workflows/backend-build.yml`（verify 步骤加一行，自 Task 4 移入）

**Interfaces:**
- Consumes: Task 4 的 verify 断言 8（本任务落地后转绿）；`REPORT_STATUS_TEXT`（constants/index.js:36-37）。
- Produces: 工单列表「上报待核对」标签；签退停留态 Modal 文案。无接口变化。

- [ ] **Step 1: `constants/index.js` 加文案**

- `REPORT_STATUS_TEXT` 加 `UNKNOWN: '上报待核对'`。
- 新增并导出 `REPORT_UNKNOWN_HINT: '签退已完成，平台上报结果待核对，请勿重复签退；管理员核对后将更新状态'`（按 UNKNOWN/停留态口径，不随瞬态 REPORTING 变化）。

- [ ] **Step 2: 工单列表标签分支**

`pkg-maintenance/pages/orders/index.js` reportTag 映射在 `FAILED` 分支后加：`UNKNOWN/REPORTING/REUPLOADING/LEGACY_UPLOADING` → `{ text: REPORT_STATUS_TEXT.UNKNOWN, cls: 'sig' }`（落 `sig` 警示色，与 FAILED 区分语义共用样式）。

- [ ] **Step 3: 签退停留态提示**

`checkout/index.js` 成功分支：`resp.reportStatus` ∈ 四态时先 `wx.showModal({ title: '签退成功', content: REPORT_UNKNOWN_HINT, showCancel: false, confirmText: '知道了' })`，确认后再 `redirectTo` 签名确认页；其余状态维持现状 toast + redirect。rework（DRAFT）路径不受影响。

- [ ] **Step 4: lint + verify 全绿 + CI 加行**

Run: `npx eslint --no-ignore app.js components config constants mock pages pkg-admin pkg-client pkg-handover pkg-maintenance scripts services utils`（worktree 内等价根 lint；主检出为 `npm run lint`）然后 `node scripts/verify-report-recovery.js`
Expected: lint 无错误；verify **全部 8 项 PASS**（断言 8 转绿），退出码 0。

CI：`.github/workflows/backend-build.yml` 的 `node scripts/verify-login-flow.js` 行后加 `node scripts/verify-report-recovery.js`。

- [ ] **Step 5: Commit**

```bash
git add constants/index.js pkg-maintenance/pages/orders/index.js pkg-maintenance/pages/checkout/index.js .github/workflows/backend-build.yml
git commit -m "新增上报待核对状态的小程序展示与签退提示"
```

---

### Task 6: admin 异常清单核对入口 + SyncView stuck 计数

**Files:**
- Modify: `admin/src/api/report.js`（新增 `reconcile`）
- Modify: `admin/src/views/FailedRecordsView.vue`（待核对集合 + 证据抽屉 + 核对按钮）
- Modify: `admin/src/ui/labels.js`（四态文案）
- Modify: `admin/src/ui/status-colors.js`（四态警示色）
- Modify: `admin/src/views/SyncView.vue`（`legacyStuck` 展示）
- Test: `admin/src/views/FailedRecordsView.test.js`

**Interfaces:**
- Consumes: Task 2 契约 `POST /platform/records/{id}/reconcile`；既有 `uploadLogs(params)`（`admin/src/api/platform.js:18-20`，`GET /reg/upload-logs`）；`/admin/records` 逗号多值过滤（AdminService:173-175 已支持）。
- Produces: 待核对记录唯一运营入口（协调审查意见 1）：可见、证据齐全（`originalRecordId`、快照 digest、`reg_upload_log` 应答、`retryCount`）、逐条「标记已上报/标记未上报」。

- [ ] **Step 1: 写失败测试（更新 `FailedRecordsView.test.js`）**

1. 加载参数改为 `reportStatus: 'FAILED,PENDING,UNKNOWN,REPORTING,REUPLOADING,LEGACY_UPLOADING'`；渲染行含 UNKNOWN/REPORTING（钉死「UNKNOWN 不进入异常清单」的旧断言反转——旧用例 `只渲染 FAILED / PENDING` 需改写为「停留态进入清单」）。
2. 停留态行显示「标记已上报」「标记未上报」按钮，FAILED 行显示「人工重报」，两者互斥。
3. 打开停留态记录抽屉：调用 `GET /reg/upload-logs`（params 含 `originalRecordId`），证据区渲染最近应答条目与 `retryCount`。
4. 点「标记已上报」→ 调 `POST /platform/records/{id}/reconcile`（body `outcome:'REPORTED'`）成功后刷新列表。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd admin && npx vitest run src/views/FailedRecordsView.test.js`
Expected: FAIL（reconcile api 不存在/过滤参数不符）。

- [ ] **Step 3: 实施**

- `api/report.js`：`export function reconcile(id, outcome, note)` → `http.post('/platform/records/' + id + '/reconcile', { outcome, note })`。
- `labels.js` 状态 map 加：`UNKNOWN:'上报待核对'`、`REPORTING:'上报中'`、`REUPLOADING:'重报中'`、`LEGACY_UPLOADING:'同步中'`；`status-colors.js` 的 `SIGNAL` 数组加这四个值（警示色）。
- `FailedRecordsView.vue`：
  - 模块级常量 `STUCK_STATUSES = ['UNKNOWN','REPORTING','REUPLOADING','LEGACY_UPLOADING']`；`load()` 参数与客户端过滤同步扩为 `FAILED,PENDING` + STUCK。
  - ViewHead sub 改 `'FAILED / PENDING / 待核对记录 · 重报与核对均逐条人工触发'`；RuleNote 补一句核对口径（先核对平台控制台再标记，标记只改本地状态）。
  - 行操作与抽屉 footer：FAILED → 既有「人工重报」；STUCK → 「标记已上报」「标记未上报」（`PermButton code="report:retry"`），点击经 ui 库既有确认/输入组件收集 note 后调 `reconcile`。
  - 抽屉加「上报应答」区：`openDetail` 时对 STUCK 行调 `uploadLogs({ originalRecordId: r.originalRecordId, size: 5 })`，渲染 `action/platformCode/platformMessage/createdAt` 列表与 `retryCount`；加载失败仅 toast 不阻断抽屉。
- `SyncView.vue`：sync 结果区在 `legacyUploaded` 后加 `legacyStuck` 行（>0 时附「请到上报异常清单逐条核对」提示），样式沿用现有 summary 行。

- [ ] **Step 4: 跑测试确认通过 + 全量 admin 门禁**

Run: `cd admin && npx vitest run src/views/FailedRecordsView.test.js src/ui/labels.test.js`
Expected: PASS。

Run: `cd admin && npm run lint && npm test && npm run build`（Windows esbuild `Access is denied` → `TMP/TEMP` 指 `admin/.tmp-build`）
Expected: 全部通过。

- [ ] **Step 5: Commit**

```bash
git add admin/src/api/report.js admin/src/views/FailedRecordsView.vue admin/src/views/FailedRecordsView.test.js admin/src/ui/labels.js admin/src/ui/status-colors.js admin/src/views/SyncView.vue
git commit -m "新增上报异常清单的待核对集合与逐条核对入口"
```

---

## Self-Review 记录

- 规格覆盖：A1 状态机→Task 1；A2 核对端点+证据→Task 2/6；A3 归档解耦→Task 3；裁定 1（工单同步）→Task 1/2；裁定 2（孤儿规则）→Task 3；裁定 3（syncLegacy 继续）→Task 1；裁定 4（uploadStatus）→Task 4；协调意见 1（清单验收）→Task 6 Step 1 用例 3；协调意见 2（constants 文案+verify 断言）→Task 4 断言 8 + Task 5。
- 类型一致性：`SyncLegacyResult(uploaded, stuck)` Task 1 定义并消费；`reconcileReport(id, outcome)`、`deleteFile(fileId)`、`reconcile(id, outcome, note)` 三端签名一致；`archived` 键 Task 3 产出、Task 4 mock、verify 断言 7 同名。
- Review Focus 五条均已落到对应任务的测试或验收步骤。
- 比例：6 任务 / 约 20 个文件改动，与设计方案等长；测试步骤只给断言名与关键值，未抄实现体。
