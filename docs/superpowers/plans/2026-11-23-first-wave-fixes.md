# 第一波四项修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 依次修复删除电梯竞态、移交签字切单、双向离职死锁和幂等 minor 两项，并分别提交。

**Architecture:** 各任务在最新 master 建独立 worktree/分支，TDD 最小修改。数据库竞态使用真实 MySQL 并发与锁/故障注入；前端沿用 detailSeq；每项独立提交且不合 master。

**Tech Stack:** Java 17、Spring Boot 3.3.4、MyBatis-Plus、MySQL 8、Vue 3、Vitest、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-11-23-first-wave-fixes-design.md`

## Global Constraints

- 不扩范围、不顺手重构；不改第四阶段独占文件。
- 不改 DispatchService 派单基线；不执行真实监管写。
- 每项先写失败测试并观察 RED，再修复和验证 GREEN。
- 每项提交仅含代码/测试；docs/superpowers 产物不提交。

## Review Focus

- 删除与派单无论谁先取锁，都不得留下引用已删电梯的在途工单。
- 签字请求与迟到响应都不得落到切换后的另一张移交单。
- 双向离职不得死锁或留下部分停用/迁移。
- 多值 query 重排应重放；测试时间在任意宿主时区都使用项目时钟。

---

### Task 1: 删除电梯与派单共享行锁

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/AdminArchiveService.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/mapper/ElevatorMapper.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/DispatchConcurrencyIntegrationTest.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/AdminArchiveServiceTest.java`

**Interfaces:**
- Consumes: DispatchService 既有电梯 `FOR UPDATE` 行锁与 `completeCheckout` 不变量。
- Produces: `ElevatorMapper.selectByIdForUpdate(String id)`；`AdminArchiveService.deleteElevator` 在事务内先锁电梯，再检查在途并删除。

- [ ] **Step 1: 写失败测试**

单测 `deleteElevatorLocksRowBeforeInflightCheck` 用 InOrder 验证行锁先于在途查询和删除。真实 MySQL 并发用例让删除方完成在途检查后暂停，再启动真实 `dispatchNow`：旧实现会建单后删除；修复后删除先完成、派单因电梯不存在被拒绝，最终无在途工单。另覆盖派单先持锁时删除被在途守卫拒绝。

- [ ] **Step 2: 验证 RED**

Run: `mvn -f backend/pom.xml "-Dtest=AdminArchiveServiceTest,DispatchConcurrencyIntegrationTest" test`

Expected: 旧实现无锁或真实并发留下工单而 FAIL；真实 MySQL 必须实际执行。

- [ ] **Step 3: 最小修复**

新增电梯行锁 mapper 查询；`deleteElevator` 添加 `@Transactional`，先锁后查再删，不改 DispatchService。

- [ ] **Step 4: 验证 GREEN 与相关回归**

Run: 同 Step 2。

Expected: 全部 PASS，真实并发用例 0 跳过。

- [ ] **Step 5: 提交**

仅 add 本任务四个文件，提交信息：`修复电梯删除与派单并发竞态`。不 add 文档，不合 master。

---

### Task 2: 移交签字防切串单

**Files:**
- Modify: `admin/src/views/HandoversView.vue`
- Test: `admin/src/ui/handover-view.test.js`

**Interfaces:**
- Consumes: 既有 `detailSeq`、`detailId`、当前 `detail` 和签字 API。
- Produces: `signNode` 在调用前及响应后均校验目标 ID/序号；切单后旧响应不刷新新单。

- [ ] **Step 1: 写失败测试**

新增测试：目标 ID 已切换时调用签字不得发 POST；A 签字 pending 时切到 B，A 完成后不得用 A 的响应刷新 B 的 signs/详情或弹旧成功提示。

- [ ] **Step 2: 验证 RED**

Run: `npm --prefix admin test -- src/ui/handover-view.test.js`

Expected: 旧 signNode 缺少目标/序号守卫而 FAIL。

- [ ] **Step 3: 最小修复**

在 `signNode` 捕获目标 ID 与 detailSeq，调用前和每个 await 后校验；迟到响应直接返回，不修改当前详情。

- [ ] **Step 4: 验证 GREEN 与管理端回归**

Run: `npm --prefix admin test -- src/ui/handover-view.test.js && npm --prefix admin run lint`

Expected: 聚焦测试与 lint PASS。

- [ ] **Step 5: 提交**

仅 add 两个文件，提交信息：`修复移交签字切换单据竞态`。不合 master。

---

### Task 3: 离职转交固定锁顺序

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/UserLifecycleService.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/service/UserLifecycleServiceTest.java`
- Create: `backend/src/test/java/com/cqwlw/maintenance/UserLifecycleConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: 既有 `lockEmployee`、事务型 `resign(actorId,targetId,heirId)`。
- Produces: target/heir 按员工 ID 字典序锁定，再按原角色读取并执行既有校验/迁移。

- [ ] **Step 1: 写失败测试**

单测用 InOrder 验证 target 较大 ID 时也先锁较小 ID。真实 MySQL 双向并发 A→B/B→A 使用 latch 让双方进入锁阶段；断言无 deadlock、无部分迁移/停用，最终状态符合序列化结果。

- [ ] **Step 2: 验证 RED**

Run: `mvn -f backend/pom.xml "-Dtest=UserLifecycleServiceTest,UserLifecycleConcurrencyIntegrationTest" test`

Expected: 旧 target→heir 顺序导致 FAIL 或真实死锁；真实 MySQL 必须执行。

- [ ] **Step 3: 最小修复**

按 targetId/heirId 字典序调用 `lockEmployee`，将返回值映射回 target/heir；不改业务校验和迁移规则。

- [ ] **Step 4: 验证 GREEN 与回归**

Run: 同 Step 2。

Expected: 全部 PASS，真实双向并发 0 跳过。

- [ ] **Step 5: 提交**

仅 add 三个文件，提交信息：`固定离职转交双方加锁顺序`。不合 master。

---

### Task 4: 幂等 query 与测试时钟 minor

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/IdempotencyService.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/IdempotencyServiceTest.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/IdempotencyLeaseIntegrationTest.java`

**Interfaces:**
- Consumes: 既有规范化指纹和 `heartbeatActive`。
- Produces: query 参数名和值均规范化；租约测试使用 `TimeUtil.now()`。

- [ ] **Step 1: 写失败测试**

新增 `multiValueQueryOrderDoesNotChangeFingerprint`：同 key、同 body、同 principal，重复参数仅值顺序不同时应重放且业务零新增调用。

- [ ] **Step 2: 验证 RED**

Run: `mvn -f backend/pom.xml "-Dtest=IdempotencyServiceTest,IdempotencyLeaseIntegrationTest" test`

Expected: 新 query 用例因值顺序不同产生冲突而 FAIL；租约测试在 UTC/Shanghai 宿主均可运行。

- [ ] **Step 3: 最小修复**

将 `getParameterMap()` 转为按参数名排序、值数组排序复制的规范化结构；`markStale` 改用 `TimeUtil.now().minusMinutes(20)`。

- [ ] **Step 4: 验证 GREEN 与全量回归**

Run: `mvn -f backend/pom.xml test`

Expected: 全部 PASS，记录环境集成测试执行/跳过。

- [ ] **Step 5: 提交**

仅 add 三个文件，提交信息：`规范幂等查询参数并统一租约测试时钟`。不合 master。
