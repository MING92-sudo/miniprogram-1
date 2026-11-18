# 权限 P2 租户/设备快照 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 记录/急修/工单冻结租户快照（V47），存量幂等回填 + 过户嫌疑人工核对，核对清零后客户读路径与 PDF 切快照谓词（P2a→P2b 两段发布）。

**Architecture:** Flyway 只做 DDL；快照写入分散在派单/签退/急修登记/修订四个触点，经共享 `TenantSnapshotService` 收口；存量回填由启动期幂等 Runner 执行，歧义进 `client_migration_issue` 清单表，经管理端最小核对视图人工裁定；客户读路径切换（P2b）是独立纯代码发布，以 issue 清零为发布门禁。

**Tech Stack:** Java 17 / Spring Boot 3.3.4 / MyBatis-Plus / Flyway（MySQL 5.7 云托管）/ JUnit5+Mockito；admin Vue3+Vite+自建组件库（ui/）+ Vitest。

**Spec:** `docs/superpowers/specs/2026-11-17-permission-p2p3-client-tenant-design.md`（§4 V47、§5、§6、§16、§17、§19-1/15/16/17/21/22）。P3 部分不在本计划内。

## Global Constraints

- 分支：`codex/permission-p2-snapshot`（AGENTS §6）；提交信息一行中文、动词开头。
- Flyway 只追加：本计划仅新增 `V47__tenant_snapshot.sql`；不得改动 V1—V46 任何迁移文件。
- 合规主干不动：签到/签退校验、动态码、上报快照、2.6 即报逻辑零改动；本计划只在既有流程上**附加**快照字段写入。
- 留存红线：迁移/回填只 UPDATE/INSERT，禁止任何 DELETE/TRUNCATE。
- **P2a 期间客户可见面零变化**：Task 1—8 不得触碰 `UnitPortalService`/`UnitRecordService` 读谓词与 `RecordPdfService`（切换只在 Task 9/10 = P2b）。
- 小程序与 mock 本计划零改动（无小程序契约变化；`/admin/migration-issues` 为管理端接口，不进 mock，先例同 M3—M5 管理端端点）。
- 错误码口径：管理端能力门禁 403（`MultiRoleGate`）；业务校验 422；数据越权 1403。
- 后端测试惯例：JUnit5 + Mockito mock mapper、构造注入、public 字段直取（参照 `UnitAccountUnifyTest`）；测试与实现同包路径。
- 每任务提交前跑：`cd backend && mvn -q test`（触碰 admin 时另跑 `cd admin && npm run lint && npm test && npm run build`，Windows esbuild `Access is denied` 时 TMP/TEMP 指向 `admin/.tmp-build`，docs/09 V3.5）。
- issue 枚举值（逐字）：issue_type ∈ `TRANSFER_SUSPECT/CODE_AMBIGUOUS/UNMATCHED`（本计划产生；`DEDUP_CONFLICT/NO_ANCHOR/CROSS_PRINCIPAL/MIXED_SKIPPED` 为 P3 预留）；object_type ∈ `RECORD/FAULT/ORDER`；resolution ∈ `PENDING/BACKFILLED/SKIPPED/MANUAL_DONE`；confidence ∈ `HIGH/MID/LOW`。
- 门控码（逐字）：核对列表读 = `PermissionMatrix.AUDIT_VIEW`；resolve 写 = `PermissionMatrix.CONFIG_MANAGE`（先例 `DataDomainController:45/51/57`）。

## Review Focus

1. **过户嫌疑电梯的在途工单签退**：工单快照 NULL 且电梯在嫌疑集 → 记录快照必须置 NULL + TRANSFER_SUSPECT issue，绝不回退当前归属（Task 4 `applyRecordSuspectStaysNullAndIssues` 钉住）。
2. **Runner 重复启动/半途失败重跑**：只处理 `use_unit_id IS NULL` 行，二次运行零写入零 issue 重复（Task 6 `secondRunIsNoop` 钉住）。
3. **resolve 双击/并发**：条件更新 `WHERE resolution='PENDING'`，第二次 422 且不重复回填（Task 2 `resolveTwiceSecondRejected422` 钉住）。
4. **同码多行电梯回填**：`ORDER BY id` 取首行（确定性）+ CODE_AMBIGUOUS issue（Task 6 `codeAmbiguousTakesFirstRowByIdAndIssues` 钉住）。
5. **P2a 客户可见面与 PDF 不变**：读路径/PDF 切换只发生在 Task 9/10；Task 9 `nullSnapshotHiddenFromClient` 钉住切换后 NULL 记录客户不可见、内部可见。

---

### Task 1: V47 迁移 + 实体快照列 + issue 实体/Mapper

**Files:**
- Create: `backend/src/main/resources/db/migration/V47__tenant_snapshot.sql`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/entity/MaintainRecord.java`（L66 `createdAt` 前追加）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/entity/Fault.java`（L51 `createdAt` 前追加）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/entity/WorkOrder.java`（L57 `deadline` 后追加）
- Create: `backend/src/main/java/com/cqwlw/maintenance/entity/ClientMigrationIssue.java`
- Create: `backend/src/main/java/com/cqwlw/maintenance/mapper/ClientMigrationIssueMapper.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/entity/V47SnapshotSqlTest.java`

**Interfaces:**
- Produces: 实体字段 `MaintainRecord.useUnitId/useUnitName/elevatorId`、`Fault.useUnitId/useUnitName`、`WorkOrder.useUnitId/useUnitName`（全部 `public String`，驼峰自动映射蛇形列）；`ClientMigrationIssue`（`@TableName("client_migration_issue")`，`@TableId public String id`，public 字段：`issueType/objectType/objectId/elevatorCode/currentUnitId/candidateUnitId/evidence/confidence/resolution/decisionJson/resolveNote/resolvedBy/resolvedAt(LocalDateTime)/createdAt(LocalDateTime)`）；`ClientMigrationIssueMapper extends BaseMapper<ClientMigrationIssue>`。后续所有任务消费这些字段。

- [ ] **Step 1: 写 V47 SQL（内容逐字取自 spec §4 V47 代码块）**

含：3 张表加列（maintain_record 三列 / fault 两列 / work_order 两列）+ 3 个 `CREATE INDEX`（idx_record_unit/idx_fault_unit/idx_order_unit）+ `CREATE TABLE client_migration_issue`（含 `decision_json TEXT NULL`、`UNIQUE KEY uk_issue (object_type, object_id, issue_type)`、`KEY idx_issue_pending (resolution, issue_type)`）。每列带 COMMENT，MySQL 5.7 方言（不用 `ADD COLUMN IF NOT EXISTS`）。

- [ ] **Step 2: 写失败测试 `V47SnapshotSqlTest`**

参照 `RbacV2MigrationTest` 的 classpath SQL 读取方式，断言 V47 文本：包含 `ALTER TABLE maintain_record`、`use_unit_name`、`elevator_id`、`CREATE TABLE client_migration_issue`、`decision_json`、`UNIQUE KEY uk_issue`、3 条 `CREATE INDEX`；断言不包含 `DELETE`/`DROP`/`TRUNCATE`（留存红线）。

```java
@Test void v47ContainsSnapshotColumnsIssueTableAndIndexes() { ... assertTrue(sql.contains("client_migration_issue")); }
@Test void v47HasNoDestructiveStatement() { ... assertFalse(sql.toUpperCase().contains("DELETE")); }
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd backend && mvn -q test -Dtest=V47SnapshotSqlTest`
Expected: FAIL（SQL 文件不存在 / 断言不成立）

- [ ] **Step 4: 加实体字段与 Mapper，跑测试确认通过 + 全量回归**

三个实体按 Produces 列出的字段名追加（public 字段、无 @TableField——驼峰自动映射）；`ClientMigrationIssue` + Mapper 新建（参照 `BizElevatorChangeMapper` 单行接口风格）。

Run: `cd backend && mvn -q test -Dtest=V47SnapshotSqlTest,MigrationIntegrityTest` → PASS；再 `mvn -q test` 全量 → 0 fail（基线 581 + 新增）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V47__tenant_snapshot.sql backend/src/main/java/com/cqwlw/maintenance/entity/ backend/src/main/java/com/cqwlw/maintenance/mapper/ClientMigrationIssueMapper.java backend/src/test/java/com/cqwlw/maintenance/entity/V47SnapshotSqlTest.java
git commit -m "feat: V47租户快照列+迁移核对清单表+实体映射（权限P2 T1）"
```

---

### Task 2: MigrationIssueService（upsert / list / resolve）

**Files:**
- Create: `backend/src/main/java/com/cqwlw/maintenance/service/MigrationIssueService.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/service/MigrationIssueServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ClientMigrationIssue(ClientMigrationIssueMapper)`、`MaintainRecordMapper/FaultMapper/WorkOrderMapper/ElevatorMapper/UseUnitMapper`、`AuditLogService`。
- Produces（Task 3/4/6/7 消费，签名逐字）:
  - `public void upsert(String objectType, String objectId, String issueType, String elevatorCode, String currentUnitId, String candidateUnitId, String evidence, String confidence)` — 按 `uk_issue(objectType,objectId,issueType)` 判存在：无则 INSERT（`id=Ids.next("mi")`，`resolution="PENDING"`，`createdAt=TimeUtil.now()`），有则仅更新 evidence/confidence（不覆盖已有 resolution/decision）。
  - `public Map<String,Object> list(String issueType, String resolution, int page, int size)` — 返回 `{list:[行视图], total, pending}`；行视图含全部列 + `decisionJson` 原样字符串。
  - `public Map<String,Object> resolve(String id, String resolution, String backfillUnitId, Map<String,Object> decision, String note, String actorEmpId)` — 见 Step 1 行为规格。

- [ ] **Step 1: 写失败测试（Mockito mock 全部 mapper，参照 UnitAccountUnifyTest 风格）**

行为规格（断言逐条钉住）：
1. `resolve` 校验：`resolution` 必须 ∈ {BACKFILLED, SKIPPED, MANUAL_DONE}，否则 422；`BACKFILLED` 必须带 `backfillUnitId` 且 `useUnitMapper.selectById` 命中，否则 422；`BACKFILLED` 仅支持 objectType ∈ {RECORD,FAULT,ORDER}（ACCOUNT 类 422，P3 才有）。
2. `BACKFILLED` 回填：按 objectType 取对应 mapper `selectById(objectId)`，写 `useUnitId=backfillUnitId`、`useUnitName=use_unit.unitName`，`updateById`；记录不存在 → 422。
3. 幂等/并发：落库用条件更新（`UpdateWrapper … eq(id).eq(resolution,"PENDING")` set resolution/decisionJson/resolveNote/resolvedBy/resolvedAt）；受影响行 0 → `BizException(422,"该问题已处理")`。
4. `decision != null` 时 `JsonUtil.write(decision)` 存 `decisionJson`（P3 Runner 消费，本期只存不读）。
5. 每次成功 resolve 调 `AuditLogService`（action=`migration:resolve`，target=issue id，result=SUCCESS，reason=note）。

```java
@Test void resolveBackfillsRecordRowWithUnitIdAndName() { ... assertEquals("uu1", saved.useUnitId); assertEquals("启元物业", saved.useUnitName); }
@Test void resolveTwiceSecondRejected422() { when(issueMapper.update(any(), any())).thenReturn(1).thenReturn(0); ... assertThrows(BizException.class, () -> svc.resolve(...第二次)); }
@Test void backfilledRequiresExistingUnit() { ... 422 }
@Test void upsertUpdatesEvidenceWithoutTouchingResolution() { ... }
@Test void resolveWritesAudit() { verify(audit)... }
```

- [ ] **Step 2: 跑测试确认失败** — `cd backend && mvn -q test -Dtest=MigrationIssueServiceTest` → FAIL（类不存在）
- [ ] **Step 3: 实现 `MigrationIssueService`**（`@Service`，构造注入 7 个依赖；实现按 Step 1 规格）
- [ ] **Step 4: 跑测试确认通过 + 全量回归** — 同命令 PASS；`mvn -q test` 0 fail
- [ ] **Step 5: Commit** — `git commit -m "feat: 迁移核对问题清单服务（upsert/list/单条resolve+审计，权限P2 T2）"`

---

### Task 3: TenantSnapshotService + 派单/排班工单快照接线

**Files:**
- Create: `backend/src/main/java/com/cqwlw/maintenance/service/TenantSnapshotService.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/DispatchService.java:179-205`（`createOrder` 内 `orderNoIssuer.insert(o)` 前）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/AdminScheduleService.java:216-230`（`orderNoIssuer.insert(o)` 前）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/DemoDataSeeder.java:466`（工单种子处）
- Test: `backend/src/test/java/com/cqwlw/maintenance/service/TenantSnapshotServiceTest.java`
- Test: 扩展 `backend/src/test/java/com/cqwlw/maintenance/DispatchServiceTest.java`、`AdminScheduleServiceTest.java`、`DemoDataSeederTest.java`

**Interfaces:**
- Consumes: Task 1 实体字段；`UseUnitMapper`、`BizElevatorChangeMapper`、Task 2 `MigrationIssueService.upsert`。
- Produces（Task 4/6 消费，签名逐字）:
  - `public void applyOrder(WorkOrder o, Elevator el)` — `o.useUnitId = el.useUnitId`；`o.useUnitName = unitNameOf(el.useUnitId)`（UseUnitMapper.selectById，未命中留 null）。
  - `public void applyFault(Fault f, Elevator el)` — 同上写 f。
  - `public void applyRecord(MaintainRecord r, WorkOrder o, Elevator el)` — 规格见 Task 4。
  - `public boolean isTransferSuspect(String elevatorId)` — `biz_elevator_change` 存在 `change_type='TRANSFER' AND status='CONFIRMED' AND elevator_id=?` 行即 true（selectCount>0）。
  - `public String unitNameOf(String useUnitId)` — null/blank 返回 null。
- 注入方式：`DispatchService`/`AdminScheduleService` 构造器加参（两者均为 Spring 单例，测试用 mock 构造）。

- [ ] **Step 1: 写失败测试 `TenantSnapshotServiceTest`**

```java
@Test void applyOrderCopiesUnitIdAndResolvesName() { el.useUnitId="uu1"; when(units.selectById("uu1")).thenReturn(unit("启元物业")); svc.applyOrder(o, el); assertEquals("uu1", o.useUnitId); assertEquals("启元物业", o.useUnitName); }
@Test void applyOrderWithoutUnitLeavesNulls() { el.useUnitId=null; ... assertNull(o.useUnitId); assertNull(o.useUnitName); }
@Test void applyFaultSameSemantics() { ... }
@Test void transferSuspectDetectedByConfirmedTransferRow() { when(changes.selectCount(any())).thenReturn(1L); assertTrue(svc.isTransferSuspect("e1")); }
```

- [ ] **Step 2: 跑测试确认失败** — `mvn -q test -Dtest=TenantSnapshotServiceTest` → FAIL
- [ ] **Step 3: 实现 `TenantSnapshotService` 并接线三处工单创建**

`createOrder`（Dispatch，自动/手动共用）与 `AdminScheduleService` 排班创建处各加一行 `tenantSnapshots.applyOrder(o, el)`（在 `orderNoIssuer.insert(o)` 之前）；两者构造器加 `TenantSnapshotService` 参数，**同步更新 `DispatchServiceTest`/`AdminScheduleServiceTest` 既有构造调用**（传 mock，`unitNameOf` 桩返回单位名）；`DemoDataSeeder:466` 工单种子直接内联 `o.useUnitId/o.useUnitName`（Seeder 上下文已有电梯与单位，不注入服务）。

- [ ] **Step 4: 扩展既有测试断言 + 跑通过**

`DispatchServiceTest`/`AdminScheduleServiceTest`：在既有「创建工单」用例上补断言 `created.useUnitId == elevator.useUnitId`（mock UseUnitMapper 返回单位名）；`DemoDataSeederTest` 补断言种子工单快照非空。Run: `mvn -q test -Dtest=TenantSnapshotServiceTest,DispatchServiceTest,AdminScheduleServiceTest,DemoDataSeederTest` → PASS

- [ ] **Step 5: 全量回归 + Commit** — `mvn -q test` 0 fail；`git commit -m "feat: 派单/排班工单冻结租户快照（TenantSnapshotService，权限P2 T3）"`

---

### Task 4: 签退记录继承 + 过户嫌疑置 NULL + 急修登记快照

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/WorkOrderService.java:795-830`（签退记录组装处，`r.workOrderId = o.id` 之后）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/DirectoryService.java:165-192`（`createFault`，`faultMapper.insert(f)` 前）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/DemoDataSeeder.java:499,527`（急修/记录种子）
- Test: 扩展 `TenantSnapshotServiceTest`、`DirectoryServiceTest`

**Interfaces:**
- Consumes: Task 3 `applyRecord/applyFault/isTransferSuspect`、Task 2 `upsert`。
- Produces: `applyRecord` 完整语义（Task 6 Runner 复用同一嫌疑判定口径）：
  1. `r.elevatorId = o.elevatorId`（o 为 null 时按 el.id）；
  2. `o.useUnitId != null` → 继承 `r.useUnitId/useUnitName = o.useUnitId/o.useUnitName`；
  3. 否则 `isTransferSuspect(el.id)` → `r.useUnitId/useUnitName` 置 NULL + `issues.upsert("RECORD", r.id, "TRANSFER_SUSPECT", r.elevatorCode, null, el.useUnitId, "工单快照缺失且电梯有过户确认记录", "LOW")`；
  4. 否则回退 `el.useUnitId` + `unitNameOf` + WARN 日志（新链路不应发生）。
- 注入方式：`WorkOrderService` 按该文件既有 `recordLifecycleService` 的可选注入模式加 `TenantSnapshotService`（null 时快照留空、不阻断签退——保护既有测试构造器）；`DirectoryService` 用其已有 `elevatorMapper`（若无需补注入）按 `elevatorCode` `selectOne … LIMIT 1` 查电梯后 `applyFault`，查不到留 NULL（Runner 兜底）。

- [ ] **Step 1: 写失败测试（扩展 TenantSnapshotServiceTest）**

```java
@Test void applyRecordInheritsOrderSnapshot() { o.useUnitId="uu1"; o.useUnitName="启元物业"; svc.applyRecord(r,o,el); assertEquals("uu1", r.useUnitId); assertEquals("启元物业", r.useUnitName); assertEquals("e1", r.elevatorId); }
@Test void applyRecordSuspectStaysNullAndIssues() { o.useUnitId=null; when(changes.selectCount(any())).thenReturn(1L); svc.applyRecord(r,o,el); assertNull(r.useUnitId); verify(issues).upsert(eq("RECORD"), eq("ur1"), eq("TRANSFER_SUSPECT"), any(), isNull(), eq("uu9"), any(), eq("LOW")); }
@Test void applyRecordFallsBackToElevatorWhenNotSuspect() { o.useUnitId=null; when(changes.selectCount(any())).thenReturn(0L); ... assertEquals("uu9", r.useUnitId); }
```

`DirectoryServiceTest` 扩展：`createFaultSetsTenantSnapshotFromElevator`（mock elevatorMapper 返回带 useUnitId 的电梯 → 断言 f.useUnitId/useUnitName；电梯缺失 → 两者 null 且照常 insert）。

- [ ] **Step 2: 跑测试确认失败** — `mvn -q test -Dtest=TenantSnapshotServiceTest,DirectoryServiceTest` → FAIL
- [ ] **Step 3: 实现 applyRecord + 两处接线 + Seeder 内联快照**（按 Produces 规格；签退处一行 `if (tenantSnapshotService != null) tenantSnapshotService.applyRecord(r, o, el);`）
- [ ] **Step 4: 跑测试确认通过 + 全量回归** — 0 fail（签退/急修既有合规用例必须全绿）
- [ ] **Step 5: Commit** — `git commit -m "feat: 签退记录继承工单快照+过户嫌疑置NULL留痕，急修登记快照（权限P2 T4）"`

---

### Task 5: amend() 复制首版快照

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/WorkDomainService.java:45-73`（v2 字段复制块）
- Test: 扩展 `backend/src/test/java/com/cqwlw/maintenance/service/WorkDomainServiceTest.java`

**Interfaces:**
- Produces: `amend()` 生成的 v2 携带 `useUnitId/useUnitName/elevatorId` = v1 同名字段（NULL 原样复制，不回查电梯）。

- [ ] **Step 1: 写失败测试**

```java
@Test void amendCopiesTenantSnapshotFromV1() {
    v1.useUnitId = "uu1"; v1.useUnitName = "启元物业"; v1.elevatorId = "e1";
    MaintainRecord v2 = svc.amend("rec1", "内容有误", "emp1");
    assertEquals("uu1", v2.useUnitId); assertEquals("启元物业", v2.useUnitName); assertEquals("e1", v2.elevatorId);
}
@Test void amendCopiesNullSnapshotWithoutElevatorLookup() { v1.useUnitId = null; ... assertNull(v2.useUnitId); verifyNoInteractions(elevatorMapper如适用); }
```

- [ ] **Step 2: 跑测试确认失败** — `mvn -q test -Dtest=WorkDomainServiceTest` → FAIL
- [ ] **Step 3: 实现**——复制块加三行 `v2.useUnitId = v1.useUnitId; v2.useUnitName = v1.useUnitName; v2.elevatorId = v1.elevatorId;`
- [ ] **Step 4: 跑测试确认通过** — PASS + 全量 `mvn -q test` 0 fail
- [ ] **Step 5: Commit** — `git commit -m "feat: 修订v2复制首版租户/电梯快照（权限P2 T5）"`

---

### Task 6: TenantSnapshotBackfillRunner（存量幂等回填）

**Files:**
- Create: `backend/src/main/java/com/cqwlw/maintenance/service/TenantSnapshotBackfillRunner.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/service/TenantSnapshotBackfillRunnerTest.java`

**Interfaces:**
- Consumes: Task 1 实体/Mapper、Task 2 `upsert`、`UseUnitMapper`、`BizElevatorChangeMapper`。
- Produces:
  - `@Service`，`@PostConstruct public void onStartup()` → 调 `backfill()`（先例 `UnitAccountUnifyService`；日志输出计数）。
  - `public Report backfill()` — `Report` 为公有静态内嵌类 `{ int orders, records, faults, issues; }`（测试直接断言）。
  - 算法（spec §6 逐字口径）：
    1. 嫌疑集 `S` = `BizElevatorChange` 中 `TRANSFER`+`CONFIRMED` 的 elevatorId Set（一次查出）；
    2. 三类对象各自循环 `selectList(isNull(use_unit_id).last("LIMIT 500"))` 直到空批（防大表一次性载入）；
    3. **ORDER**：`elevatorMapper.selectById(o.elevatorId)`；el∈S → upsert TRANSFER_SUSPECT(ORDER, LOW)；el 命中 → 回填两列（HIGH）；el 缺失 → UNMATCHED(LOW)；
    4. **RECORD**：优先 `workOrderMapper.selectById(r.workOrderId)`（一次性迁移允许逐行查）——工单快照非空 → 继承（HIGH）；否则按 `r.elevatorCode` 查电梯 `selectList(eq(elevatorCode)).orderByAsc(id)`：el∈S → TRANSFER_SUSPECT；多行 → 取首行回填 + CODE_AMBIGUOUS(MID)；单行 → 回填（HIGH）；无行 → UNMATCHED；`elevator_id` 同步回填（el.id）；
    5. **FAULT**：按 `elevatorCode` 同 RECORD 第 4 步口径；
    6. 回填行的 `use_unit_name = unitNameOf(use_unit_id)`；回填用 `updateById` 只写快照列。
  - 幂等：入口谓词即 `isNull(use_unit_id)`，二次运行空批直接返回。

- [ ] **Step 1: 写失败测试**

```java
@Test void backfillsOrderFromElevatorUnit() { ... assertEquals(1, rep.orders); assertEquals("uu1", savedOrder.useUnitId); assertEquals("启元物业", savedOrder.useUnitName); }
@Test void transferSuspectNotBackfilledAndIssued() { assertNull(savedRecord.useUnitId); verify(issues).upsert(eq("RECORD"), eq("ur1"), eq("TRANSFER_SUSPECT"), ...); }
@Test void recordInheritsWorkOrderSnapshotFirst() { o.useUnitId="uu2"; ... assertEquals("uu2", savedRecord.useUnitId); }
@Test void codeAmbiguousTakesFirstRowByIdAndIssues() { 两行同码电梯 id=e1,e2 ... assertEquals("e1对应单位", ...); verify(issues).upsert(..., eq("CODE_AMBIGUOUS"), ..., eq("MID")); }
@Test void unmatchedElevatorLeavesNullAndIssues() { ... eq("UNMATCHED") }
@Test void secondRunIsNoop() { 首批返回行、次批空 → 再调 backfill() 全 mock 无新 update/insert }
```

- [ ] **Step 2: 跑测试确认失败** — `mvn -q test -Dtest=TenantSnapshotBackfillRunnerTest` → FAIL
- [ ] **Step 3: 实现 Runner**（按 Produces 算法；嫌疑集与单位名 map 可一次加载缓存于本次 backfill 调用内）
- [ ] **Step 4: 跑测试确认通过 + 全量回归** — 0 fail
- [ ] **Step 5: Commit** — `git commit -m "feat: 租户快照存量幂等回填Runner（过户嫌疑/同码歧义/无法判定进人工清单，权限P2 T6）"`

---

### Task 7: AdminMigrationIssueController（列表 + 单条 resolve）

**Files:**
- Create: `backend/src/main/java/com/cqwlw/maintenance/controller/AdminMigrationIssueController.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/controller/AdminMigrationIssueControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `list/resolve`；`MultiRoleGate.requireRead/requireWrite`；`PermissionMatrix.AUDIT_VIEW/CONFIG_MANAGE`；actor 取法参照 `DataDomainController`（request attribute）。
- Produces（Task 8 admin 消费，响应 `{code,message,data}` 包装）:
  - `GET /admin/migration-issues?type=&resolution=&page=1&size=20` → `data = {list,total,pending}`；
  - `POST /admin/migration-issues/{id}/resolve` body `{resolution, backfillUnitId?, decision?, note}` → `data = {ok:true}`；无批量端点。

- [ ] **Step 1: 写失败测试**（参照 `RecordControllerTest` 的 mock request/roles 构造）

```java
@Test void listRequiresAuditViewDeny403() { 角色无 AUDIT_VIEW → BizException 403 }
@Test void resolveRequiresConfigManageDeny403() { ... }
@Test void resolvePassesBodyAndActorToService() { verify(svc).resolve(eq("mi1"), eq("BACKFILLED"), eq("uu1"), isNull(), eq("已核对"), eq("emp1")); }
@Test void listReturnsServicePage() { ... }
```

- [ ] **Step 2: 跑测试确认失败** → FAIL
- [ ] **Step 3: 实现 Controller**（两个端点 + 门控两行 + 委托 service）
- [ ] **Step 4: 跑测试确认通过 + 全量回归** — 0 fail
- [ ] **Step 5: Commit** — `git commit -m "feat: 迁移核对清单管理端点（audit:view读/config:manage单条resolve，权限P2 T7）"`

---

### Task 8: admin 迁移核对视图（P2a 最小交付）

**Files:**
- Create: `admin/src/api/migration.js`
- Create: `admin/src/views/MigrationIssuesView.vue`
- Create: `admin/src/views/MigrationIssuesView.test.js`
- Modify: `admin/src/router/index.js:57-59`（`数据与系统` 组，audit 路由之后插入）

**Interfaces:**
- Consumes: Task 7 两端点；admin 既有 `api/client.js` http 封装（参照 `api/platform.js` 导出风格）、`ui/` DataTable/Modal/PermButton/Toast、路由 `meta.perm` 数据驱动菜单（无需另配菜单文件）。
- Produces:
  - `migration.js`: `export function listMigrationIssues(params)` → GET；`export function resolveMigrationIssue(id, body)` → POST。
  - 路由：`{ path: 'migration-issues', component: MigrationIssuesView, meta: { title: '迁移核对', group: '数据与系统', perm: 'audit:view' } }`。
  - 视图：筛选（issueType/resolution 下拉 + 查询）、DataTable 列（类型/对象/电梯码/当前单位/候选单位/证据/置信度/状态）、行操作「裁定」按钮（`PermButton perm="config:manage"`）→ Modal（resolution 三选、backfillUnitId 输入、note 必填校验）→ 提交刷新列表；PENDING 计数展示在页头（ViewHead/PageMeta 风格参照既有视图）。

- [ ] **Step 1: 写失败测试 `MigrationIssuesView.test.js`**（参照既有 `admin/src/views/*.test.js` 的 mock api + mount 风格）

```js
test('渲染清单并展示 pending 计数', ...)   // mock listMigrationIssues 返回 2 行 → 表格 2 行、计数正确
test('无 config:manage 权限不渲染裁定按钮', ...)
test('裁定提交调用 resolveMigrationIssue 并刷新', ...) // BACKFILLED 缺 backfillUnitId → 前端拦截不发请求
```

- [ ] **Step 2: 跑测试确认失败** — `cd admin && npm test -- MigrationIssuesView` → FAIL
- [ ] **Step 3: 实现 api/路由/视图**
- [ ] **Step 4: 跑测试确认通过 + admin 三件套** — `npm test` 全绿（基线 125 + 新增）；`npm run lint`；`npm run build`（TMP 规避见 Global Constraints）
- [ ] **Step 5: Commit** — `git commit -m "feat: 管理端迁移核对视图（清单+单条裁定，权限P2 T8）"`

---

### Task 9: P2b——客户读路径切快照谓词 + NULL 隐藏

> **发布门禁（runbook，非代码）**：本任务产物单独发布（P2b），发布前 `client_migration_issue` 中 RECORD/FAULT 类 PENDING 必须清零（核对视图确认）。

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/UnitPortalService.java:115-121,160-165`（`visibleRecords`/`faults`）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/UnitRecordService.java:55-104,174-179,220-225`（`pendingAs`/`requireOwnUnit`/`unitOf`）
- Test: 扩展 `UnitPortalServiceTest`、`UnitRecordServiceTest`、`service/ClientRecordGateTest`

**Interfaces:**
- Produces（P3 计划依赖的最终口径）:
  - `UnitPortalService.visibleRecords(useUnitId)`：过滤条件由「elevatorCode ∈ ownCodes」改为 `useUnitId.equals(r.useUnitId)`；`use_unit_id` NULL 行天然不命中（客户隐藏、内部端点不受影响）。
  - `UnitPortalService.faults(useUnitId)`：`useUnitId.equals(f.useUnitId)`；电梯列表（`elevators()`）仍按当前归属不变（spec §5）。
  - `UnitRecordService.unitOf(r)`：改为 `return r.useUnitId == null ? "" : r.useUnitId;`（L174 注释已预留「P2 改记录快照列」；删除 elevatorMapper 回查）。
  - `UnitRecordService.pendingAs`：`elevatorUnitMap` 批量归属链替换为 `r.useUnitId` 直接过滤（保留 N+1 治理成果——不再需要电梯查询）。

- [ ] **Step 1: 写失败测试**

```java
// UnitPortalServiceTest
@Test void recordsFilteredBySnapshotUnitNotElevatorCode() { 记录 code 属 A 梯但快照 uuB → uuA 查询不含、uuB 查询含 }
@Test void nullSnapshotHiddenFromClient() { r.useUnitId=null → records/pendingSigns 均不含 }
@Test void faultsFilteredBySnapshotUnit() { ... }
// UnitRecordServiceTest
@Test void getAsCrossUnitBySnapshot1403() { r.useUnitId="uuB", token uuA → BizException 1403，无副作用 }
@Test void pendingAsUsesSnapshotColumnWithoutElevatorQuery() { verify(elevatorMapper, never()).selectList(any()) }
```

- [ ] **Step 2: 跑测试确认失败** — `mvn -q test -Dtest=UnitPortalServiceTest,UnitRecordServiceTest,ClientRecordGateTest` → FAIL
- [ ] **Step 3: 实现切换**（按 Produces 四处；`ownCodes`/`elevatorUnitMap` 若仍被电梯列表等使用则保留，仅解除记录/急修过滤对它们的依赖）
- [ ] **Step 4: 跑测试确认通过 + 全量回归** — 0 fail（ClientRecordGateTest 既有门禁用例必须全绿）
- [ ] **Step 5: Commit** — `git commit -m "feat: 客户读路径切租户快照谓词+NULL隐藏（P2b，权限P2 T9）"`

---

### Task 10: P2b——归档/PDF 单位名称用快照

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/RecordPdfService.java:129-150`（`deviceRows`）及其调用方（`build`/归档链路，含 `RecordLifecycleService.archive` 传参处）
- Test: 扩展 `backend/src/test/java/com/cqwlw/maintenance/service/RecordPdfServiceTest.java`

**Interfaces:**
- Produces: `deviceRows(Elevator el, UseUnit uu, String snapshotUnitName)` — 「使用单位」单元格 = `snapshotUnitName` 非空用之；为空（NULL 快照）→ 固定文案 `"归属待核对"`（内部标注；客户侧因 Task 9 NULL 隐藏拿不到该 PDF）。调用方传 `r.useUnitName`。急修单 PDF 的 `deviceRowsOfMap`（L153-155）本期不动（fault 快照名进视图字段属 P3 客户急修口径，`fault.use_unit_name` 已落库可用，视图接线随 P3）。

- [ ] **Step 1: 写失败测试**

```java
@Test void deviceRowUsesSnapshotUnitName() { r.useUnitName="启元物业（快照）"; uu.unitName="改名后物业"; → 断言 PDF 文本/行数组含 "启元物业（快照）" 不含 "改名后物业"（按 RecordPdfServiceTest 既有断言方式） }
@Test void nullSnapshotShowsPendingLabel() { r.useUnitName=null; → 含 "归属待核对" }
```

- [ ] **Step 2: 跑测试确认失败** — `mvn -q test -Dtest=RecordPdfServiceTest` → FAIL
- [ ] **Step 3: 实现**（签名加参 + 调用方传 `r.useUnitName`；`uu` 参数保留供其余字段）
- [ ] **Step 4: 跑测试确认通过 + 全量回归** — 0 fail（RecordPdfSignedUrlTest 等 PDF 既有用例全绿）
- [ ] **Step 5: Commit** — `git commit -m "feat: 维保记录PDF使用单位改记录快照名（P2b，权限P2 T10）"`

---

### Task 11: 文档同步 + 全量门禁

**Files:**
- Modify: `docs/04-接口文档.md`（A 部分新增小节 + 版本行）
- Modify: `docs/12-未完成事项清单.md`（I 组 P2 行 + 修订记录）
- Modify: `AGENTS.md`（§0 基线计数 + §10 修订记录一行）
- Modify: `docs/14-账号权限与组织角色设计.md`（§12 P2 行状态 + §14 修订记录一行）

**Interfaces:**
- Consumes: 全部前序任务的实测计数（`mvn test` 总数、Flyway 最高版本）。

- [ ] **Step 1: docs/04 同步**——A 部分按现有编号追加「迁移核对」小节：`GET /admin/migration-issues`（query 参数/响应结构/403 口径）与 `POST /admin/migration-issues/{id}/resolve`（body/422 口径/审计）；记录/急修/工单内部视图新增 `useUnitId/useUnitName/elevatorId` 字段说明（只增不改）；标注 V47 与 P2a/P2b 发布语义；版本表追加一行（当前最新 V2.64 → 新增 V2.65）。小程序契约无变化 → `services/`、`mock/server.js` 不动（在版本行注明「三处同步不适用：无小程序契约变更」）。
- [ ] **Step 2: docs/12 回写**——I 组 P2 行：🔲 待排期 → 「P2a ✅（写入链+回填+核对视图）/ P2b ✅（读路径+PDF 切快照，发布门禁：issue 清零）」附门禁证据（单测数、admin 用例数）；修订记录追加 V1.37。
- [ ] **Step 3: AGENTS + docs/14 回写**——AGENTS §0 基线（Flyway V1—V47、单测数以 `mvn test` 实测为准、admin 视图 28 个）+ §10 追加 V3.31 一行（P2 落地摘要）；docs/14 §12 P2 行状态 + §14 修订记录 V2.4。
- [ ] **Step 4: 全量门禁**

Run（全部必须绿，逐项记录输出）:
```bash
cd backend && mvn -q clean test
npm run lint
node scripts/verify-dispatch.js && node scripts/verify-templates.js
node scripts/verify-miniapp-routes.js && node scripts/verify-miniapp-m0.js && node scripts/verify-m1-sync.js
node scripts/verify-review-fixes.js && node scripts/verify-p1-mock.js
cd admin && npm run lint && npm test && npm run build
```
Expected: 后端 0 fail/0 error（≥581+新增）；各 verify 脚本全 PASS；admin lint/test/build 绿。

- [ ] **Step 5: Commit** — `git commit -m "docs: 权限P2租户快照落地回写（docs/04 V2.65/docs/12 V1.37/AGENTS V3.31/docs/14 V2.4）"`

---

## 发布切分备忘（执行完≠上线完）

- **P2a 发布** = Task 1—8 产物（快照写入 + 回填 + 核对视图）；上线后人工核对 issue 至 RECORD/FAULT 类清零。
- **P2b 发布** = Task 9—10 产物（客户读路径 + PDF 切快照）；发布前置 = 上述清零（runbook 门禁，见 Task 9 头部）。
- 两次发布之间的间隔期行为 = 现网口径不变（客户仍按电梯当前归属），这是 spec §19-1 的设计意图，不是缺陷。
