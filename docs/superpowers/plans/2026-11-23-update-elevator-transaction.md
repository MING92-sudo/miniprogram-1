# updateElevator 两写事务 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 确保电梯主更新与显式清 NULL 第二写同成同败。

**Architecture:** 在既有 Service 公共方法增加 `@Transactional`；用事务代理单测和真实 MySQL mapper 故障注入验证。

**Tech Stack:** Java 17、Spring Boot 3.3.4、Spring Transaction、MyBatis-Plus、MySQL 8、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-11-23-update-elevator-transaction-design.md`

## Global Constraints

- 仅修改 `updateElevator` 及既有测试；不改接口、其他业务方法或迁移。
- 使用随机隔离 MySQL 数据库，不操作真实数据，不执行监管写接口。
- 不提交代码，除非后续用户明确选择合并/PR。

## Review Focus

- 第二写失败必须同时撤销名称和坐标变化。
- 正常清空坐标仍需显式 SET NULL，不能因事务改动失效。

---

### Task 1: updateElevator 原子更新

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/AdminArchiveService.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/AdminArchiveServiceTest.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/AdminArchiveTransactionIntegrationTest.java`

**Interfaces:**
- Consumes: 既有 `transactionalProxy(...)`、真实 MySQL mapper 故障代理和 `updateElevator(String,Map)`。
- Produces: `updateElevator` 签名与返回结构不变，两个写入通过 Spring 事务执行。

- [ ] **Step 1: 写失败测试**

新增单测 `updateElevatorRollsBackWhenExplicitClearFails`：主更新成功后显式 update 抛错，断言 rollback、无 commit。

扩展集成测试 `updateElevatorClearFailureRestoresOriginalRow`：原始名称/坐标固定，第二写注入失败，使用独立 JDBC 连接断言两者均保持原值。保留正常更新与清坐标回归。

- [ ] **Step 2: 验证旧实现失败**

Run: `mvn -f backend/pom.xml "-Dtest=AdminArchiveServiceTest,AdminArchiveTransactionIntegrationTest" test`

Expected: 新用例因无 rollback 且真实行部分更新而 FAIL；真实 MySQL 未执行不得计为通过。

- [ ] **Step 3: 实施最小修复**

仅在 `AdminArchiveService.updateElevator` 添加 `@Transactional`，不改其他代码路径。

- [ ] **Step 4: 验证聚焦测试**

Run: 同 Step 2。

Expected: 全部 PASS，真实 MySQL 新用例不得跳过。

- [ ] **Step 5: 全量回归与差异检查**

Run: `mvn -f backend/pom.xml test`

Expected: 全部 PASS；记录集成测试执行/跳过情况。

Run: `git diff --check && git status --short`

Expected: 无空白错误，仅本阶段文件变化；不提交代码。
