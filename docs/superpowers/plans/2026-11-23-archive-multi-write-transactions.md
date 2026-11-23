# 档案管理多写事务边界 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐员工建档/编辑、使用单位删除和批量坐标保存的事务边界，后续写失败时不留下部分提交。

**Architecture:** 保持 `AdminArchiveService` 既有写入顺序和公开契约，只在四个多写公共入口启用 Spring 事务。Mockito 事务代理测试覆盖每个后续写失败，隔离 MySQL 集成测试用真实 Flyway schema 和 mapper 故障代理验证实际回滚。

**Tech Stack:** Java 17、Spring Boot 3.3.4、Spring Transaction、MyBatis-Plus、MySQL 8、Flyway、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-11-23-archive-multi-write-transactions-design.md`

## Global Constraints

- 仅修改后端服务与测试；不改接口、权限、前端、迁移或依赖。
- `batchGeo` 的 `BizException`/`NumberFormatException` 仍逐条进入 `failed[]`，合法项正常提交；未捕获异常必须整体回滚。
- 班组变更审计失败仍被既有 `appendGroupChangeAudit` 吞并，不阻断主流程。
- `deleteElevator` 只有单次删除，本任务不改；不处理校验—删除并发竞态。
- 真实 MySQL 验证只能使用随机一次性数据库，测试结束删除；连接信息仅由环境变量提供。
- 未经用户明确要求不执行 Git commit/push。

## Review Focus

- 角色插入发生在员工写入之后：失败必须同时撤销员工主记录及已删除的旧角色行。
- 清空 `platformId`/`regionId` 依赖第二次显式 update：第二次失败不得留下只完成主记录更新的状态。
- 删除使用单位有三个写入阶段：任一阶段失败都要保留单位、账号和记录引用原状。
- `batchGeo` 业务失败与数据库失败语义不同：前者允许部分成功，后者必须整批回滚。
- 审计是 best-effort：补事务后仍须验证审计异常不会把员工更新变成失败。

---

### Task 1: 档案多写原子性与真实回滚证据

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/AdminArchiveService.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/AdminArchiveServiceTest.java`
- Create: `backend/src/test/java/com/cqwlw/maintenance/AdminArchiveTransactionIntegrationTest.java`

**Interfaces:**
- Consumes: 既有 `AdminArchiveService` 构造器、`setClientAccountMapper(...)`、`setMaintainRecordMapper(...)` 和 `AdminArchiveServiceTest.transactionalProxy(...)`。
- Produces: `createEmployee`、`updateEmployee`、`deleteUseUnit`、`batchGeo` 保持原签名与返回结构，并通过 Spring 事务代理执行。

- [ ] **Step 1: 写事务代理失败测试**

在 `AdminArchiveServiceTest` 将角色、客户账号和维保记录 mapper 提升为可配置 mock，复用 `transactionalProxy(...)`。新增：
- `createEmployeeRollsBackWhenPrimaryRoleInsertFails`：员工 insert 后角色 insert 抛错，断言 rollback、无 commit。
- `updateEmployeeRollsBackWhenExplicitNullClearFails` 与 `updateEmployeeRollsBackWhenPrimaryRoleInsertFails`：分别让第二次显式 update、角色 insert 抛错，断言 rollback。
- `deleteUseUnitRollsBackWhenRecordDetachFails`、`deleteUseUnitRollsBackWhenClientAccountDeleteFails`、`deleteUseUnitRollsBackWhenUnitDeleteFails`：三阶段分别抛错，均断言 rollback。
- `batchGeoRollsBackWhenLaterUpdateFails`：第一次 update 成功、第二次抛未捕获 `IllegalStateException`，断言 rollback。
- `batchGeoBusinessFailuresKeepPartialSuccess`：合法项与越界经纬度混合，断言 `success`/`failed` 各一项且 commit。
- `groupAuditFailureDoesNotRollbackEmployeeUpdate`：`auditLogService.append` 抛错，断言员工更新正常返回且 commit。

- [ ] **Step 2: 写隔离 MySQL 集成测试**

新建 `AdminArchiveTransactionIntegrationTest`，以 `ARCHIVE_IT_MYSQL_SERVER_URL`（仅允许以 `/` 结尾的服务器 URL）、`ARCHIVE_IT_MYSQL_USERNAME`、`ARCHIVE_IT_MYSQL_PASSWORD` 启用。仿照 `DispatchConcurrencyIntegrationTest` 创建随机数据库、执行全部 Flyway 迁移、注册所需 mapper、用 `DataSourceTransactionManager` 创建真实事务代理，并在 `@AfterAll` 删除随机库。

用 JDBC 准备最小业务行，用 JDK mapper 代理在指定调用次数抛 `IllegalStateException`，覆盖：
- `createEmployeeRoleFailureLeavesNoEmployeeRow`。
- `updateEmployeeRoleFailureRestoresEmployeeAndRoleRows`。
- `deleteUseUnitFailureRestoresUnitAccountsAndRecordLinks`。
- `batchGeoFailureRollsBackEarlierCoordinateUpdates`。
- `batchGeoBusinessFailuresKeepValidRows`。

每个失败用例直接用 JDBC 断言所有相关表保持原状；成功用例提交后用新连接断言最终状态。

- [ ] **Step 3: 验证新测试在旧实现上失败**

Run: `mvn -f backend/pom.xml "-Dtest=AdminArchiveServiceTest" test`

Expected: 新增事务测试至少一项因未 rollback/commit 而 FAIL，既有测试不被人为改成通过。

配置一次性 MySQL 环境后再运行：

Run: `mvn -f backend/pom.xml "-Dtest=AdminArchiveTransactionIntegrationTest" test`

Expected: 四个失败注入用例在旧实现上留下部分数据并 FAIL；若本地无 MySQL，记录未执行并继续寻找既有隔离实例，不得把环境跳过当作通过。

- [ ] **Step 4: 实施最小事务修复**

仅在 `AdminArchiveService` 的 `createEmployee`、`updateEmployee`、`deleteUseUnit`、`batchGeo` 公共方法添加 `@Transactional`。不改写入顺序、异常捕获、审计语义或 `deleteElevator`。

- [ ] **Step 5: 复测聚焦用例**

Run: `mvn -f backend/pom.xml "-Dtest=AdminArchiveServiceTest,EmployeeRoleAssignmentTest,AdminArchiveTransactionIntegrationTest" test`

Expected: 单测全部 PASS；配置一次性 MySQL 时集成测试全部 PASS，否则明确列出集成测试未执行。

- [ ] **Step 6: 全量后端回归与差异检查**

Run: `mvn -f backend/pom.xml test`

Expected: 全量 PASS；环境未配置导致的集成测试跳过不计为真实数据库验收通过。

Run: `git diff --check && git status --short`

Expected: 无空白错误；仅计划文档、本任务服务与测试发生变化，不覆盖既有未提交工作。最终报告真实 MySQL 的执行证据或明确缺口，不提交代码。
