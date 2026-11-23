# 幂等响应口令脱敏 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 阻止初始口令/密码进入幂等响应存储，并用追加迁移安全清理既有数据。

**Architecture:** `Guard.commit` 在序列化前用 Jackson 树递归删除口令字段，控制器首次响应仍使用原对象。新增 V69 仅更新 `idempotency_key.response_json`；真实 MySQL 测试从 V68 植入样本后迁移至最新版本验证。

**Tech Stack:** Java 17、Spring Boot 3.3.4、Jackson、MyBatis-Plus、MySQL 8、Flyway、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-11-23-idempotency-secret-redaction-design.md`

## Global Constraints

- 首次响应及其原始对象不变；仅持久化与后续重放脱敏。
- 递归处理对象和数组，目标字段精确为 `initialPassword`、`password`。
- V69 只追加，不修改旧迁移；不删除幂等键、不修改账号口令、不执行业务重试。
- 无效但含目标字段的 JSON 替换为 `{"redacted":true}`；其他记录保持不变。
- 不操作生产数据库，不执行真实监管写接口；未经用户明确要求不提交代码。

## Review Focus

- 原始结果含不可变嵌套集合：脱敏必须在 Jackson 树上进行，不得修改原对象。
- 数组内嵌套对象含口令：递归不得只清根字段。
- 损坏 JSON 含口令：不得遗留下次重放可读的秘密，也不得删除键导致业务重执行。
- 无口令及无效但无口令的响应：V69 不得误改。
- 迁移顺序含 V67.1：真实 MySQL 必须先迁移至 V68，再植入样本并迁移到最新版本。

---

### Task 1: 运行时响应递归脱敏

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/IdempotencyService.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/IdempotencyServiceTest.java`

**Interfaces:**
- Consumes: 既有 `JsonUtil.MAPPER`、`JsonUtil.write(Object)` 和 `IdempotencyKeyMapper.updateById(...)`。
- Produces: `Guard.commit(Object result)` 签名不变；写入 mapper 的 `responseJson` 为递归脱敏 JSON。

- [ ] **Step 1: 写失败测试**

新增 `commitRedactsSecretsWithoutMutatingOriginalResult`：结果含根级 `initialPassword`、`password` 和普通 `id`；捕获 mapper 更新行，断言 JSON 无两个口令字段、保留 `id`，原 Map 仍含原口令值。

新增 `commitRedactsNestedSecretsInObjectsAndArrays`：在嵌套对象及数组对象放置口令字段，断言持久化 JSON 的所有层级均无目标字段，普通嵌套字段保留。

新增 `replayedResultContainsNoPersistedSecrets`：先 commit 含口令结果，再以捕获的持久化 JSON 构造完成行并调用 `begin` 同键，断言重放成功、普通字段一致、口令字段不存在。

- [ ] **Step 2: 验证旧实现失败**

Run: `mvn -f backend/pom.xml "-Dtest=IdempotencyServiceTest" test`

Expected: 新测试因持久化/重放 JSON 仍含口令而 FAIL。

- [ ] **Step 3: 实现最小递归脱敏**

在 `IdempotencyService` 内将结果转换为 Jackson `JsonNode`，递归遍历对象和数组并移除 `initialPassword`、`password`，再序列化写入 `responseJson`；不改变 `commit` 签名和控制器返回值。

- [ ] **Step 4: 验证单测通过**

Run: 同 Step 2。

Expected: 全部 PASS，既有 3 项契约测试无回归。

---

### Task 2: V69 存量口令清理

**Files:**
- Create: `backend/src/main/java/db/migration/V69__redact_idempotency_response_secrets.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/IdempotencySecretMigrationIntegrationTest.java`

**Interfaces:**
- Consumes: V2 的 `idempotency_key(idem_key,path,response_json,created_at)`；V69 只更新 `response_json`。
- Produces: 最新 Flyway 迁移后，有效 JSON 递归删除目标字段并保留其他数据；无法解析的敏感响应变为 `{"redacted":true}`；键和其他记录不变。

- [ ] **Step 1: 写真实 MySQL 迁移失败测试**

创建以 `IDEMPOTENCY_IT_MYSQL_SERVER_URL`、`IDEMPOTENCY_IT_MYSQL_USERNAME`、`IDEMPOTENCY_IT_MYSQL_PASSWORD` 启用的集成测试。URL 只能指向服务器，测试创建随机数据库并执行 Flyway `target("68")`，插入以下键后迁移至最新：
- `valid-root`：根级口令字段，验证仅口令被移除。
- `valid-nested`：嵌套对象/数组含口令，验证语义迁移递归移除口令并保留其他字段；另含 Unicode 转义字段名、仅值文本相同的对照样本。
- `invalid-secret`：损坏 JSON 且含 `initialPassword`，验证变为 `{"redacted":true}`。
- `plain`：有效无口令 JSON，验证原文不变。
- `invalid-plain`：损坏但无口令文本，验证原文不变。

测试 `v69RedactsExistingSecretsSafely` 以 JDBC 直接读取五行，断言所有键均存在。

- [ ] **Step 2: 验证迁移缺失时失败**

Run: `mvn -f backend/pom.xml "-Dtest=IdempotencySecretMigrationIntegrationTest" test`

Expected: 配置隔离 MySQL 后测试因敏感字段仍在而 FAIL；环境未配置不得计为通过。

- [ ] **Step 3: 新增 V69 Java 迁移**

使用 Flyway `BaseJavaMigration` 和既有 Jackson  mapper 逐行读取并语义解析；有效 JSON 递归删除目标字段，无法解析但含目标字段名（含 Unicode 转义）时替换为安全标记。仅在内容变化时更新，校验影响行数；不得使用 DELETE，不得修改其他表。

- [ ] **Step 4: 验证真实迁移**

Run: 同 Step 2。

Expected: 集成测试 1/1 PASS，测试结束随机库删除。

- [ ] **Step 5: 全量回归与差异检查**

Run: `mvn -f backend/pom.xml test`

Expected: 全部 PASS；分别记录运行时测试与真实迁移测试数量及环境跳过。

Run: `git diff --check && git status --short`

Expected: 无空白错误，仅本阶段设计/计划、幂等 Service、单测、V69 和迁移测试变化；不提交代码。
