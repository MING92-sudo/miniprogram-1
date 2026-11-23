# 幂等核心原子性与租约设计

## 目的

修复业务提交与幂等完成分离、键跨接口/用户/请求体重放、10 分钟长请求被接管及响应互相覆盖的问题，同时保留离线补传和进程崩溃后的恢复能力。

## 目标

1. 纯数据库端点的键预留、业务 DB 写入、完成响应处于同一事务，任一失败全部回滚。
2. 幂等键绑定请求方法、实际 URI、操作者和规范化请求体摘要；同键不同指纹拒绝，不执行业务。
3. 每次执行有唯一 owner；完成与释放都按 owner 条件写，禁止跨执行覆盖。
4. 外部副作用端点不使用数据库长事务；活跃执行通过 heartbeat 保持租约，只有心跳停止的悬挂记录才可接管。
5. 保留第一阶段响应脱敏；响应列扩为 LONGTEXT，避免大响应超过 TEXT。
6. 既有无指纹记录 fail-closed：不重放、不删除、不自动接管、不重新执行业务。

## 非目标与禁止事项

- 本阶段不修改 `updateElevator` 两写事务，不实现客户签字/签退的业务恢复策略；后续阶段处理。
- 不把监管 HTTP、平台查询、PDF 渲染或文件存储放入数据库事务。
- 不新增平台自动重试；UNKNOWN 禁止重报，FAILED 仅逐条人工重报。
- 不改变整改页面已为每次整改生成独立 `reworkAttemptKey` 的既有机制。
- 不打印完整幂等键、token、请求体、openid 或口令。

## 方案比较

- **采用：统一执行器 + 强指纹 + owner/heartbeat 租约。** 能同时覆盖跨层原子性、错误重放、长请求接管和崩溃恢复。
- **未采用：悬挂记录一律永久拒绝。** 实现较简单，但进程崩溃后离线任务需要人工清理，不满足自动恢复目标。
- **未采用：控制器直接加事务。** 会把外部 I/O 混入事务，并把基础设施职责散落在控制器。

## 核心接口

在 `IdempotencyService` 提供两个泛型入口，替代控制器的 `begin/commit` 顺序调用：

```java
<T> T executeDb(String key, Object body, HttpServletRequest request, Supplier<T> action)
<T> T executeExternal(String key, Object body, HttpServletRequest request, Supplier<T> action)
```

- 无 key：直接执行业务，保持既有可选头兼容。
- 两个入口均使用请求方法与 `request.getRequestURI()`，不再信任静态路径模板。
- 指纹为 SHA-256：请求方法、实际 URI、规范化 query、规范化 body、操作者类型与 ID。Jackson 对 Map 递归排序后序列化，确保仅 Map 键顺序不同的请求指纹一致；List 顺序保留。query 按参数名和值排序，匿名分享链的 `token` 因而纳入摘要；不得保存 token 或 query 原文。
- 操作者取 `ATTR_PRINCIPAL_TYPE` + `ATTR_EMP_ID`；旧单位令牌使用 `ATTR_UNIT_ID`；匿名链路使用固定匿名类型。指纹只存摘要，不存原始身份信息或请求体。
- `executeDb` 使用 `TransactionTemplate` 包裹预留、业务和完成；既有 Service 事务加入同一事务。
- `executeExternal` 仅在预留、完成、释放时分别使用短事务，业务回调在事务外执行。

## V70 数据结构与旧记录

追加 `V70__idempotency_atomic_lease.sql`：

- `fingerprint CHAR(64) NULL`
- `owner_token VARCHAR(64) NULL`
- `heartbeat_at DATETIME NULL`
- `response_json` 改为 `LONGTEXT NULL`

主键仍为 `idem_key`。新记录的 `fingerprint`、`owner_token`、`heartbeat_at` 必填于应用写入路径。

旧记录这些字段为 NULL。任何同键新请求都返回 422「历史幂等记录需人工核对」，既不重放也不执行。这样不会让旧离线操作误重放其他主体结果，也不会因清键而重复建单；人工核对后再决定后续操作。

## 预留、重放与接管

1. **完成记录**：指纹相同才返回脱敏重放；指纹不同拒绝。
2. **活跃记录**：指纹相同且 heartbeat 未过期返回 422「正在处理」；指纹不同拒绝。
3. **悬挂记录**：仅指纹相同、响应为空、heartbeat 已超过 10 分钟才允许接管。
4. 接管使用带旧 owner、空响应和过期时间的条件 DELETE；影响行数必须为 1，再插入新 owner。任一失败表示已有其他请求接管，返回 422。
5. 新键插入的主键冲突继续转为 422，不进入业务。
6. 业务异常时按 owner 删除空响应记录，使确定的失败可立即重试；owner 不匹配则不删除他人记录。

## 完成与响应

- 完成写使用 `idem_key + owner_token + response_json IS NULL` 条件 UPDATE，影响行数必须为 1。
- 0 行或异常均向外抛出；`executeDb` 因业务与幂等在同一事务而整体回滚。
- `executeExternal` 不承诺外部副作用可回滚；本阶段只保证键作用域、owner 防护和错误不被吞掉，业务恢复由后续阶段处理。
- 响应继续经第一阶段递归口令脱敏后写入 LONGTEXT。

## Heartbeat

- 复用现有 `@EnableScheduling`，每 60 秒对活跃的 `executeExternal` 执行按 owner 条件更新 `heartbeat_at`。
- 活跃表使用 `ConcurrentHashMap<idemKey, Execution>`；结束用 `remove(key, execution)`，避免旧执行移除新 owner。
- 心跳写要求记录仍为空响应且 owner 匹配；失败不打印敏感值，可在下一周期重试。
- `executeDb` 的预留记录在外层事务提交前对其他事务不可见，活跃长事务不存在可被接管的已提交悬挂行，因此不需要心跳。

## 控制器迁移

将现有 6 个控制器中的 begin/commit 调用全部迁移到统一执行器：

- `executeDb`：档案、客户账号开通、急修/救援/演练/检查、工单本地状态、门户急修等纯 DB 端点。
- `executeExternal`：两个主体同步查询、客户单条/批量/匿名签字、工单签退。
- 控制器返回首次业务结果；重放结果由执行器直接返回，既有 `{code,message,data}` 包装不变。

## 测试与验收

### 单元/契约

- 同键同指纹完成重放；不同方法、URI、query（含匿名 token）、body、principal 均冲突且业务零调用。
- Map 键顺序不同仍可重放，List 顺序不同必须冲突。
- 旧记录 fail-closed；正常并发同键只有一方进入业务。
- 业务异常释放仅删同 owner；完成异常/0 行向外抛出。
- 第一阶段口令脱敏测试继续通过。

### 真实 MySQL

- 业务插入后强制完成写失败/0 行，业务行、键预留与响应均回滚。
- 活跃外部执行以 latch 暂停；人工将 heartbeat 调旧后调用 heartbeat，另一同键请求仍被拒绝，不重复执行。
- 无活跃 heartbeat 的过期同 owner 记录可被接管；旧 owner 后完成必须失败且不能覆盖新响应。
- 旧记录既不重放也不执行；V70 后响应列为 LONGTEXT。

### 全量

- 后端 `mvn test`、根 lint、受影响小程序/离线脚本按实际改动执行。
- 不执行真实平台写接口；未做的真机/人工走查必须明确列出。

## 留给实施者的决定

- 使用既有 `Ids`/UUID 生成 owner，复用现有 mapper/transaction 测试模式。
- 在不改变公开响应的情况下选择最小内部实现；不顺手重构业务 Service。
