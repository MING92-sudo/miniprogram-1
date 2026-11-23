# 幂等核心原子性与租约 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让业务与幂等簿记具备正确事务边界、请求作用域和可恢复执行租约，消除错误重放、重复执行与跨 owner 覆盖。

**Architecture:** `IdempotencyService` 提供泛型 `executeDb`/`executeExternal`。前者用同一 `TransactionTemplate` 包裹预留、业务和完成；后者仅短事务管理键生命周期，并用 owner + heartbeat 保护外部执行。V70 增加指纹/owner/heartbeat，响应扩为 LONGTEXT，旧记录 fail-closed。

**Tech Stack:** Java 17、Spring Boot 3.3.4、Spring Transaction、Jackson、MyBatis-Plus、MySQL 8、Flyway、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-11-23-idempotency-core-atomicity-design.md`

## Global Constraints

- 指纹覆盖方法、实际 URI、规范化 query、规范化 body、principal；query/token 原文不得落库或入日志。
- 旧记录缺新字段时既不重放也不执行，返回 422 人工核对；不得删除旧键。
- 完成/释放/接管均校验 owner 与影响行数；完成失败不得吞掉。
- 外部 HTTP、平台查询、PDF、文件存储不得进入数据库长事务。
- 保留第一阶段口令脱敏；不改变整改独立 `reworkAttemptKey`。
- UNKNOWN 禁止重报、FAILED 仅逐条人工重报；不运行真实监管写接口。
- 未经用户明确要求不提交代码。

## Review Focus

- 同键不同匿名 token：必须冲突且业务零调用，不能只比较 URI/body。
- 业务已成功但完成写失败：DB 路径必须整体回滚，外部路径不得删除键或伪装成功。
- 长请求 heartbeat 与接管并发：活跃执行不得被接管，旧 owner 不得覆盖新响应。
- 旧完成记录：不得因迁移而重放错误主体或因清键而重复执行。
- Map 与 List 规范化：Map 键顺序不影响指纹，List 顺序变化必须冲突。

---

### Task 1: V70 与指纹化键生命周期

**Files:**
- Create: `backend/src/main/resources/db/migration/V70__idempotency_atomic_lease.sql`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/entity/IdempotencyKey.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/mapper/IdempotencyKeyMapper.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/IdempotencyService.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/IdempotencyServiceTest.java`

**Interfaces:**
- Consumes: 既有 `IdempotencyKeyMapper`、`JsonUtil.MAPPER`、`TimeUtil.now()`、第一阶段 `sanitizedResponseJson`。
- Produces: `IdempotencyKey` 增加 `fingerprint/ownerToken/heartbeatAt`；mapper 提供 owner 条件的 complete/release/heartbeat/takeover；新增泛型 `executeDb`/`executeExternal` 基础入口支持完成重放、指纹冲突、活跃拒绝、过期接管和旧记录拒绝。旧 begin/commit 暂保留给控制器，Task 3 移除。

- [ ] **Step 1: 写生命周期失败测试**

在 `IdempotencyServiceTest` 覆盖：
- `completedSameFingerprintReplays`：完成行仅在方法、URI、query、body、principal 全一致时重放。
- `differentRequestFingerprintConflictsWithoutExecuting`：分别改变 URI、body、query token、principal，业务 Supplier 均零调用。
- `canonicalMapOrderDoesNotChangeFingerprintButListOrderDoes`。
- `legacyRecordFailsClosedWithoutReplayOrExecution`：无指纹的旧完成/悬挂记录均 422，mapper 不 delete。
- `freshPendingRecordRejectsDuplicate` 与 `stalePendingRecordCanBeTakenOver`。
- `completionFailurePropagatesAndNeverDeletesReservation`。
- 保留第一阶段全部口令脱敏断言。

- [ ] **Step 2: 验证旧实现失败**

Run: `mvn -f backend/pom.xml "-Dtest=IdempotencyServiceTest" test`

Expected: 新指纹/owner/旧记录用例 FAIL；不得通过删除旧断言制造通过。

- [ ] **Step 3: 实现 V70 与核心生命周期**

V70 增加 `fingerprint CHAR(64)`、`owner_token VARCHAR(64)`、`heartbeat_at DATETIME`，并将 `response_json` 改为 LONGTEXT。Service 用 SHA-256 计算规范化请求指纹；每次预留生成唯一 owner。完成、释放、接管使用 mapper 条件写并检查影响行数；旧记录 fail-closed。提供 `executeDb`/`executeExternal` 基础实现供测试和后续任务使用，此阶段尚不为前者加外层事务、尚不为后者加 heartbeat。

- [ ] **Step 4: 验证生命周期测试**

Run: 同 Step 2。

Expected: 全部 PASS，含第一阶段回归。

---

### Task 2: `executeDb` 同事务原子性

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/IdempotencyService.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/IdempotencyServiceTest.java`
- Create: `backend/src/test/java/com/cqwlw/maintenance/IdempotencyDbAtomicityIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的指纹化预留、owner 条件完成与 V70 schema。
- Produces: `<T> T executeDb(String key, Object body, HttpServletRequest request, Supplier<T> action)`；无 key 直接执行，有 key 时预留+业务+完成同一事务。

- [ ] **Step 1: 写事务与真实 MySQL 失败测试**

单测验证无 key 直接执行、重放不调用业务、业务异常向外传播。集成测试使用随机 MySQL 库和真实 `DataSourceTransactionManager`：
- `dbBusinessAndCompletionCommitTogether`：回调插入真实档案行，完成后新连接可见业务行与完成响应。
- `dbCompletionFailureRollsBackBusinessAndReservation`：在业务插入后让幂等完成写抛错，断言业务行和键均不存在。
- `dbCompletionZeroRowsRollsBackBusiness`：完成写返回 0，断言同样无半成品。
- `dbBusinessFailureRollsBackReservationAndBusiness`：回调先写后抛错，断言两者均回滚。

- [ ] **Step 2: 验证旧实现失败**

Run: `mvn -f backend/pom.xml "-Dtest=IdempotencyServiceTest,IdempotencyDbAtomicityIntegrationTest" test`

Expected: Task 1 的 `executeDb` 尚未把预留、业务和完成纳入同一事务，导致 FAIL；真实 MySQL 未执行不得计为通过。

- [ ] **Step 3: 实现 `executeDb`**

注入 `TransactionTemplate`，在同一个 `execute` 回调内调用 Task 1 预留、业务 Supplier 和完成；不重试业务，不在 catch 中吞完成异常。

- [ ] **Step 4: 验证 DB 原子性**

Run: 同 Step 2。

Expected: 单测和真实 MySQL 集成测试全部 PASS，随机库清理。

---

### Task 3: 外部执行租约与六控制器迁移

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/IdempotencyService.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/AdminArchiveController.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/ClientAccountController.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/DirectoryController.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/UnitPortalController.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/UnitRecordController.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/WorkOrderController.java`
- Test: 相关控制器现有测试
- Create: `backend/src/test/java/com/cqwlw/maintenance/IdempotencyLeaseIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1/2 的生命周期与 `executeDb`。
- Produces: Task 1 的 `executeExternal` 增加 owner heartbeat；所有既有 begin/commit 端点改用对应执行器，HTTP 路径和首次响应不变。

- [ ] **Step 1: 写外部执行与租约失败测试**

单测覆盖：业务异常仅释放同 owner；完成失败不释放、不吞掉；重放不调用外部 Supplier。

真实 MySQL `IdempotencyLeaseIntegrationTest` 使用 latch：
- `activeHeartbeatPreventsStaleTakeover`：A 暂停执行，人工将 heartbeat 调旧后调用 heartbeat，B 同键仍 422，A 正常完成。
- `staleOwnerTakeoverRejectsOldCompletion`：无活跃 heartbeat 的过期记录由 B 接管；恢复 A 后旧 owner 完成失败，数据库保留 B 的响应。
- `externalCompletionFailureDoesNotReleaseKey`：业务返回后完成写失败，键仍归原 owner，不能自动重跑。
- `legacyRecordNeitherReplaysNorExecutes`：旧记录保持原状且 Supplier 零调用。

- [ ] **Step 2: 验证旧实现失败**

Run: `mvn -f backend/pom.xml "-Dtest=IdempotencyServiceTest,IdempotencyLeaseIntegrationTest" test`

Expected: 外部执行器/owner 防护缺失导致 FAIL；真实 MySQL 未执行不得计为通过。

- [ ] **Step 3: 实现 `executeExternal` 与 heartbeat**

预留和完成使用独立短事务；业务回调在事务外。业务异常才释放同 owner 空响应记录，完成异常不释放。复用 `@EnableScheduling`，每 60 秒按 owner 更新活跃外部执行 heartbeat，结束用 `remove(key, execution)`。

- [ ] **Step 4: 迁移六个控制器**

所有 begin/commit 调用替换为执行器。仅主体同步、客户签字、工单签退使用 `executeExternal`，其余现有本地 DB 端点使用 `executeDb`；请求 body 和 `HttpServletRequest` 传给执行器，不再传静态 path。不得改变业务调用参数或响应包装。

- [ ] **Step 5: 验证外部租约与控制器回归**

Run: `mvn -f backend/pom.xml "-Dtest=IdempotencyServiceTest,IdempotencyLeaseIntegrationTest,UnitRecordConfirmTest,ClientRecordGateTest,WorkOrderCheckoutDuoTest,CheckoutConcurrencyGuardTest" test`

Expected: 全部 PASS；真实租约测试不得跳过。

- [ ] **Step 6: 全量回归与差异检查**

Run: `mvn -f backend/pom.xml test`

Expected: 全部 PASS，记录各真实 MySQL 套件和环境跳过。

Run: `npm run lint`

Expected: 小程序根 lint PASS；若未改小程序仍执行作为回归。

Run: `git diff --check && git status --short`

Expected: 无空白错误，仅本阶段文件变化；不提交代码。
