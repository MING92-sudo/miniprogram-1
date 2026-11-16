# 权限模块 P1 重构实施计划（语义与安全门禁）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 docs/14 V2.2 落地 P1——把「使用单位=外部客户租户」的语义与安全门禁接进现有权限模块，关闭客户令牌越权访问内部端点/字段/签字的缺口，不改数据库表、不动登录主体二分、内部 11 角色行为逐条不变。

**Architecture:** 以 `RoleCatalog.PrincipalType`（EMPLOYEE/CUSTOMER）为语义底座；`AuthInterceptor` 客户主体路由白名单为最强 enforcement（一处关闭 /users /org /stats /audit /invites 等未注册 AdminRoleInterceptor 的内部端点缺口）；新三态矩阵 `STATES` 收回 CA/CS 内部能力（冻结 legacy GRANTS 不动）；客户记录签字/拒签加生命周期+最新版本门禁与 `toClientMap` 字段白名单；员工/组织/统计临时按角色排除客户；客户工作台下线报修/评价入口。矩阵/菜单为手工镜像，Java↔JS↔config↔docs/16 必须同改并过漂移守卫。

**Tech Stack:** Java 17 / Spring Boot 3.3.4 / MyBatis-Plus / JUnit5（backend）；CommonJS 根矩阵 + Vue3/Vite ESM 桥 + Vitest（admin）；微信小程序 CommonJS + miniprogram-automator（scripts/e2e）；mock/server.js（Node）。

**Spec:** `docs/14-账号权限与组织角色设计.md` V2.2（§1.3 默认决策、§3 角色、§4.6/4.7 签字门禁与白名单、§5.4 能力码、§7.3 路由边界、§12 P1 行、§9.2 步骤 7）。P1 边界以 §12 P1 行为准；P2/P3/P4（快照列、client_account 迁出、/client/** 新端点、登录选单位、新增 client:record_view/refuse 码）**不在本计划**。

## Global Constraints

- 不改数据库表、不新增 Flyway 迁移（P1 不改表；签字门禁复用 V42 已有 `lifecycle_status/confirm_status/version/prev_version_id` 列）。
- 不动登录主体二分：仍 `/auth/login` 一入口一 token；不新增 `/client/**` 业务端点、不新增 `select-unit`/`needUnitSelection`（P3）。
- 内部 11 角色（含旧 5 角色）放行结果逐条不变；冻结面 `RbacMatrixTest`/`AdminRoleInterceptorCompatTest`/`LegacyCapabilityTranslatorTest`/legacy `GRANTS` **禁改**。
- 矩阵码总数保持 **86**、规范角色 **13**、旧 `codes()` **12**；SYS_ADMIN 全码 Y 循环保留；fail-closed（未知角色 DENY、未知码抛 IAE、每码≥1 持有者）保留。
- 不新增能力码（`client:record_view`/`client:refuse` 属 P3/P4）；P1 客户签字/拒签能力判定复用 `client:sign`。
- 合规红线（AGENTS §2/§3）：客户签字/拒签**不触发平台 2.6 重报、不改签退上报快照**；越权一律 403/1403 且无副作用；不打印 token/手机号/openid/平台密钥；`originalRecordId` 禁自动重试。
- 接口三处同步（AGENTS §1.2）：改接口须 `services/` + `mock/server.js` + `docs/04` A 部分同步；菜单改动须过 `verify-miniapp-routes.js` 双源守卫。
- 错误码/文案从 `constants/index.js` 取；时间 `yyyy-MM-dd HH:mm:ss`（GMT+8）；POST/PUT/PATCH 自带 `X-Idempotency-Key`。
- 验证命令：backend `mvn test`（surefire `-Djdk.attach.allowAttachSelf=true`）；admin `npm run lint && npm test && npm run build`（Windows esbuild Access denied 时 TMP/TEMP→admin/.tmp-build）；根 `npm run lint`；`node scripts/verify-miniapp-routes.js`、`verify-miniapp-m0.js`、`verify-m1-sync.js`、`verify-review-fixes.js`、`verify-dispatch.js`、`verify-templates.js`。
- 分支 `codex/permission-p1`（AGENTS §6）；提交一行中文动词开头；禁凭证/报文入库。

## Review Focus

1. **混合角色令牌**：roles 同时含内部角色与客户角色（异常/历史数据）时，白名单判定不得把内部员工误锁成客户，也不得让客户借内部角色绕过——`isCustomer` 定义为「roles 非空且全部 principalType=CUSTOMER」，含任一内部角色即走员工路径（fail-closed 由矩阵/归属兜底）。T3 测试钉死。
2. **签字并发与旧版本**：两个客户账号并发签同一记录、或持记录链旧版本 id 签字——先签为准、后签 422/只读，旧版本不可签，状态与签字字段不变。T4 测试钉死。
3. **客户详情字段泄漏**：签退生成的记录含 `context/reportPayload/shareToken/平台ID`，客户详情/列表/sign-view 任一路径都不得返回；`refuseReason`（内部复核退回）不与客户拒签原因混回。T4/T7 字段级断言。
4. **跨单位数量/列表泄漏**：`badges.pendingSigns`、`/unit/records/pending`、`/unit/faults`、`/unit/elevators`、`home/summary.platformTotal` 不得跨单位或全平台计数/返回。T5/T7 钉死本单位收敛。
5. **镜像漂移**：Java STATES 改了而 JS 根镜像/prototype-mock/config/menus/docs16 未同步——计数守卫过但实格分歧。T2/T6 补客户格抽样断言 + 跑双源守卫。

---

### Task 1: RoleCatalog 主体类型底座

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/auth/RoleCatalog.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/auth/RoleCatalogV2Test.java`（更新）+ 新增 `RoleCatalogPrincipalTypeTest.java`

**Interfaces:**
- Produces: `enum PrincipalType { EMPLOYEE, CUSTOMER }`；`public static PrincipalType principalTypeOf(String anyCode)`（未知/null→null）；`departmentOf` 对客户主体返回 null；`assignableRoles()` 按 `principalType==EMPLOYEE` 过滤。后续 T3/T4/T5/T6 用 `principalTypeOf` 判客户。

- [ ] **Step 1: 写失败测试** `RoleCatalogPrincipalTypeTest`

```java
assertEquals(PrincipalType.CUSTOMER, RoleCatalog.principalTypeOf("CLIENT_ADMIN"));
assertEquals(PrincipalType.CUSTOMER, RoleCatalog.principalTypeOf("CLIENT_SIGNER"));
assertEquals(PrincipalType.CUSTOMER, RoleCatalog.principalTypeOf("UNIT_ADMIN")); // 别名归一
assertEquals(PrincipalType.EMPLOYEE, RoleCatalog.principalTypeOf("MAINT_TECH"));
assertEquals(PrincipalType.EMPLOYEE, RoleCatalog.principalTypeOf("ADMIN"));
assertNull(RoleCatalog.principalTypeOf("HACKER"));
assertNull(RoleCatalog.principalTypeOf(null));
assertNull(RoleCatalog.departmentOf("CLIENT_ADMIN"));
assertNull(RoleCatalog.departmentOf("UNIT_ADMIN")); // P1：客户不再属 UNIT 部门
assertEquals("MAINT", RoleCatalog.departmentOf("WORKER")); // 内部不变
assertTrue(RoleCatalog.assignableRoles().stream().noneMatch(r -> r.code().startsWith("CLIENT")));
assertFalse(RoleCatalog.canUseAdminConsole("CLIENT_ADMIN")); // 保持 false
```

- [ ] **Step 2: 跑测试确认失败** Run: `mvn test -Dtest=RoleCatalogPrincipalTypeTest`，Expected: 编译失败（PrincipalType 不存在）。

- [ ] **Step 3: 实现** 在 RoleCatalog 增 `PrincipalType` 枚举与 `principalTypeOf`：经 `canonical(anyCode)` 归一后，`CLIENT_ADMIN/CLIENT_SIGNER`→CUSTOMER，其余规范码→EMPLOYEE，null→null。CANONICAL 表 CLIENT_* 的 `department` 由 `"UNIT"` 改 `null`（L86-87）。`departmentOf(role)`：先 `principalTypeOf(role)==CUSTOMER`→null，否则走旧 ROLES 表（内部不变）。`assignableRoles()`：过滤条件由 `!UNIT.equals(department)` 改为 `principalTypeOf(r.code())==EMPLOYEE`（旧 ROLES 表 UNIT_ADMIN→CUSTOMER 被排除，CLIENT_* 不在旧表本就不入）。`canUseAdminConsole` 不动（客户已 false）。`departmentName` 保留 UNIT 分支作历史展示。

- [ ] **Step 4: 跑测试确认通过** Run: `mvn test -Dtest=RoleCatalogPrincipalTypeTest,RoleCatalogV2Test,RbacMatrixTest`，Expected: PASS。RoleCatalogV2Test 若断言 CLIENT_* department=UNIT 需改为 null（L42 adminConsole=false 保持）；RbacMatrixTest `roleCatalogMatchesDesignDoc`（旧 12 码部门）不受影响（旧表 UNIT_ADMIN 部门字段保留 UNIT，仅 departmentOf 投影改 null）。

- [ ] **Step 5: 提交** `git add backend/src/main/java/.../RoleCatalog.java backend/src/test/java/.../RoleCatalog*.java && git commit -m "feat: RoleCatalog 增主体类型，客户角色部门归 null 且不可分配"`

---

### Task 2: 新三态矩阵收回客户内部能力（Java + JS 镜像 + 守卫）

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/auth/PermissionMatrix.java`（STATES 段 L335-427）
- Modify: `constants/permission-matrix.js`（STATES 段，逐字同改）
- Modify: `admin/src/ui/prototype-mock.js`（MATRIX_RAW 客户列对齐，见 DC4）
- Test: `backend/src/test/java/.../auth/PermissionMatrixV2Test.java`（更新客户格断言）
- Test: `admin/src/ui/permission-v2.test.js`（补客户格抽样）
- Test: `admin/src/ui/phase2.test.js`（若断言客户格则同步）

**Interfaces:**
- Consumes: T1 `principalTypeOf`（仅语义参考，矩阵改的是 STATES 行）。
- Produces: 客户最小能力集——`client:elevator_view`/`client:download`/`client:sign` 对 CA+CS 均 Y；`client:report`/`client:evaluate`/`client:user_manage`/`client:invite` 对 CA/CS DENY；CA/CS 对全部内部码 DENY。`state/allowsNew` 签名不变。T6 菜单 actions、T7 mock perms 据此收敛。

**决策（我的调用，标记供审）：**
- DC2：P1 不新增码（86 不变）；`client:sign` 由「CS Y / CA DENY」改「CA+CS Y」（doc §5.4 两角色能力相同）。
- DC3：`client:refuse` 码 P3 才增；P1 拒签能力判定复用 `client:sign`。
- DC4：`prototype-mock.js` MATRIX_RAW 客户列（CADMIN/CSIGN）对齐新最小集（client:sign 两角色 Y、report/evaluate/user_manage DENY、内部码 DENY），消除与根镜像分歧；它是演示基准集非权威，phase2 不变量若涉及客户格同步改。

- [ ] **Step 1: 写失败测试**（PermissionMatrixV2Test 增样格）

```java
// 客户最小集
assertEquals(TriState.Y, PermissionMatrix.state("CLIENT_ADMIN", "client:sign"));   // 原 DENY→Y
assertEquals(TriState.Y, PermissionMatrix.state("CLIENT_SIGNER", "client:sign"));
assertEquals(TriState.Y, PermissionMatrix.state("CLIENT_ADMIN", "client:elevator_view"));
assertEquals(TriState.Y, PermissionMatrix.state("CLIENT_ADMIN", "client:download"));
// 默认关闭
assertEquals(TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "client:user_manage")); // 原 Y
assertEquals(TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "client:report"));
assertEquals(TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "client:evaluate"));
assertEquals(TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "client:invite"));
// 内部码全收回（抽样）
for (String code : new String[]{"user:view","user:create","user:disable","stats:view","stats:export",
    "audit:view","role:assign","invite:manage","order:create","order:view_own","order:confirm",
    "record:view","record:sign","record:refuse","record:export","handover:view","handover:export",
    "project:view","contract:view","elevator:view","elevator:qr","elevator:export","plan:view"}) {
  assertEquals(TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", code), code);
  assertEquals(TriState.DENY, PermissionMatrix.state("CLIENT_SIGNER", code), code);
}
assertEquals(86, PermissionMatrix.canonicalCapabilities().size()); // 码数不变
assertEquals(TriState.Y, PermissionMatrix.state("SYS_ADMIN", "client:user_manage")); // SA 全 Y 保留
```

admin `permission-v2.test.js` 增镜像样格（`state('CLIENT_ADMIN','client:sign')==='Y'`、`state('CLIENT_ADMIN','user:create')==='DENY'`、`state('CLIENT_ADMIN','client:user_manage')==='DENY'`、86 码/13 角色计数保持）。

- [ ] **Step 2: 跑确认失败** Run: `mvn test -Dtest=PermissionMatrixV2Test`（CA client:sign 原 DENY、user_manage 原 Y→FAIL）；`cd admin && npx vitest run src/ui/permission-v2.test.js`（FAIL）。

- [ ] **Step 3: 实现 Java STATES** 删除 CA/CS 在所有内部码行的出现（L335-427 中含 `CA`/`CS` 的 `y(...)`/`c(...)` 参数里移除 CA/CS）；客户段改为：`y(CLIENT_ELEVATOR_VIEW, CA, CS)`、`y(CLIENT_DOWNLOAD, CA, CS)`、`y(CLIENT_SIGN, CA, CS)`；删除 `y(CLIENT_INVITE, CA, MS)` 中的 CA（保留 MS）、`y(CLIENT_USER_MANAGE, CA)`、`y(CLIENT_REPORT, CA, CS)`、`y(CLIENT_EVALUATE, CA, CS)` 整行（这些码仅剩 SA 持有，fail-closed 满足）。**不动 legacy GRANTS（L73-190）、不动 86 计数校验、不动 SA 循环**。注意 `RECORD_SIGN/RECORD_REFUSE/ORDER_CONFIRM` 移除 CS 后仅 SA 持有（合法）。

- [ ] **Step 4: JS 镜像逐字同改** `constants/permission-matrix.js` 对应行做完全相同增删（client:sign 改 `y('client:sign', CA, CS)`；删 client:user_manage/report/evaluate 行；client:invite 去 CA；内部码行去 CA/CS）。prototype-mock.js MATRIX_RAW 客户列按 DC4 对齐。

- [ ] **Step 5: 跑确认通过** Run: `mvn test -Dtest=PermissionMatrixV2Test,RbacMatrixTest,LegacyCapabilityTranslatorTest`（冻结面绿）；`cd admin && npm test`（permission-v2 + phase2 绿）。Expected: PASS。

- [ ] **Step 6: 提交** `git add backend/.../PermissionMatrix.java constants/permission-matrix.js admin/src/ui/prototype-mock.js backend/.../PermissionMatrixV2Test.java admin/src/ui/permission-v2.test.js admin/src/ui/phase2.test.js && git commit -m "feat: 客户矩阵收回内部能力，最小集 elevator_view/download/sign，报修评价账号管理默认关闭"`

---

### Task 3: AuthInterceptor 客户主体路由白名单

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/auth/AuthInterceptor.java`（员工分支 L90-95）
- Test: `backend/src/test/java/.../auth/AuthInterceptorV2Test.java`（增客户白名单用例）

**Interfaces:**
- Consumes: T1 `RoleCatalog.principalTypeOf`；ATTR_ROLES（Set<String> 规范码）。
- Produces: `private boolean isCustomer(Set<String> roles)`（非空且全部 CUSTOMER）；客户令牌业务白名单放行，其余 403。T5/T6 内部端点拒客户由此兜底。

**白名单（客户主体，P1 兼容 /unit/**，我的调用 DC2）：**
- 放行前缀：`/unit/`、`/files/`（GET）、`/me/menus`、`/auth/me`、`/auth/change-password`、`/auth/bind-wechat`、`/auth/logout`、`/health`；`GET /elevators`（含 `/elevators/{id}/profile`，本单位谓词在 ElevatorController 已有）。
- 其余一律 403「客户账号仅可访问客户门户接口」（尤其 `/users /org /stats /audit /invites /grants /projects /roles-matrix /admin/ /reg/ /platform/ /employees/ /use-units/ /home/summary`）。
- 旧 ut=1 门户分支白名单（L64-67）保持，仅同步追加 `/me/menus`、`/auth/me` 等自身账号端点（若缺）。

- [ ] **Step 1: 写失败测试**（AuthInterceptorV2Test，模拟 CLIENT_ADMIN 员工统一令牌）

```java
// 客户令牌打内部端点 → 403
for (String uri : new String[]{"/users","/org","/stats","/audit","/invites","/admin/dashboard","/employees","/home/summary"}) {
  // request roles=Set.of("CLIENT_ADMIN"), client=mp, sid/sv 合法
  assertFalse(interceptor.preHandle(req(uri), resp, handler)); assertEquals(403, resp.getStatus());
}
// 客户门户与自身账号端点放行
for (String uri : new String[]{"/unit/records/pending","/me/menus","/auth/me","/elevators"}) {
  assertTrue(interceptor.preHandle(req(uri), resp, handler));
}
// 内部员工不受影响（MAINT_TECH 打 /work-orders 放行）
assertTrue(interceptor.preHandle(req("/work-orders", Set.of("MAINT_TECH")), resp, handler));
// 混合角色（含内部）走员工路径，不被误锁
assertTrue(interceptor.preHandle(req("/users", Set.of("MAINT_MGR","CLIENT_ADMIN")), resp, handler));
```

- [ ] **Step 2: 跑确认失败** Run: `mvn test -Dtest=AuthInterceptorV2Test`，Expected: 客户打 /users 原放行→FAIL。

- [ ] **Step 3: 实现** 在员工分支写完 attributes 后（L94 后、return true 前）：`if (isCustomer(roles) && !clientAllowed(uri, method)) { response 403; return false; }`。`isCustomer` = roles 非空 && 全部 `principalTypeOf==CUSTOMER`。`clientAllowed` 按上列白名单前缀/方法判定。复用现有 403 写法（参照 ut=1 分支 L64-67）。

- [ ] **Step 4: 跑确认通过** Run: `mvn test -Dtest=AuthInterceptorV2Test,AuthInterceptorTest,UnitTokenScopeTest,AdminRoleInterceptorCompatTest`，Expected: PASS（UnitTokenScopeTest 旧 ut=1 用例不受影响；内部角色用例不变）。

- [ ] **Step 5: 提交** `git commit -am "feat: 客户主体令牌业务路由白名单，内部端点拒客户（兼容 /unit/**）"`

---

### Task 4: 客户记录签字/拒签门禁 + toClientMap 字段白名单

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/UnitRecordService.java`（pendingAs/getAs/confirmAs/unitOf + 新增 toClientMap）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/RecordLifecycleService.java`（refuseByToken 状态门禁）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/UnitRecordController.java`（详情/列表改用 toClientMap）
- Test: `backend/src/test/java/.../service/UnitRecordServiceTest.java`、`UnitRecordConfirmTest.java`（更新）+ 新增 `ClientRecordGateTest.java`

**Interfaces:**
- Consumes: T1 principalTypeOf；V42 列 `lifecycleStatus/confirmStatus/version/prevVersionId`。
- Produces: `private Map<String,Object> toClientMap(MaintainRecord r)`（白名单投影）；`private boolean isLatestVersion(MaintainRecord r)`（不存在 prevVersionId=r.id 的后续版本）；签字门禁链。T7 mock 对齐同一白名单与门禁。

**门禁链（doc §4.6 规则 2，签字/拒签前依次校验，任一失败 403/1403/422 无副作用）：**
principalType=CUSTOMER → token useUnitId → record 归属（P1 仍按 unitOf 电梯归属，P2 才改快照）→ `lifecycleStatus=PENDING_CLIENT_SIGNATURE` → `confirmStatus=PENDING` → isLatestVersion → 账号启用。

**toClientMap 白名单（doc §4.7，P1 用现有列）：**
返回：id、version、prevVersionId、lifecycleStatus、elevatorCode、elevatorName、workType、workTypeCode、workerName、assistantName、checkinTime、checkoutTime、duration、items（名称/结果/实测值/照片URL）、photos、problemCodes、todoDesc、workerSignatureUrl、assistantSignatureUrl、confirmStatus、signatureUrl、pdfUrl（如有）、clientRefuseReason（仅 REFUSED_RETURNED，P1 无独立列则暂不返回，见 DC5）。
**明确不返回**：reportStatus、uploadStatus、retryCount、reportPayload、context/previewContext、shareToken、originalRecordId、workerPlatformId、assistantPlatformId、refuseReason（内部复核退回）、内部复核意见、幂等键、平台日志、satisfaction。
- DC5（我的调用）：P1 无 `client_refuse_reason` 独立列（P3 增），客户拒签原因 P1 暂不回显（避免与内部 refuseReason 混回）；内部 refuseReason 一律不返回客户。

- [ ] **Step 1: 写失败测试** `ClientRecordGateTest`

```java
// 非待签状态签字 → 422，状态/签字字段不变
// 构造 lifecycleStatus=PENDING_CLIENT_SIGN（双签未齐）记录，confirmAs → BizException 422
// 旧版本签字 → 422（存在 prevVersionId=v1.id 的 v2）
// 并发先签为准：confirmStatus=PENDING 条件更新，第二次 confirm → 422/已签，不覆盖首签
// 字段白名单：toClientMap 不含 reportPayload/context/shareToken/originalRecordId/workerPlatformId/refuseReason
Map<String,Object> m = service.getAs(id, clientRoles, unitId);
for (String k : new String[]{"reportPayload","context","previewContext","shareToken","originalRecordId","workerPlatformId","assistantPlatformId","refuseReason","reportStatus","uploadStatus","satisfaction"})
  assertFalse(m.containsKey(k), k);
// 拒签原因必填：refuseByToken reason 空 → 422；非 PENDING_CLIENT_SIGNATURE → 422
```

- [ ] **Step 2: 跑确认失败** Run: `mvn test -Dtest=ClientRecordGateTest`，Expected: FAIL（原 pendingAs 仅筛 confirmStatus、toMap 吐内部字段）。

- [ ] **Step 3: 实现** pendingAs 过滤增 `lifecycleStatus=PENDING_CLIENT_SIGNATURE` + isLatestVersion；getAs/confirmAs 前置门禁链；confirmAs 落库用条件更新（`WHERE confirm_status='PENDING' AND lifecycle_status='PENDING_CLIENT_SIGNATURE'`，受影响行 0→422 已签）；新增 toClientMap 白名单投影，UnitRecordController 详情/列表/pending 改调 toClientMap（内部 toMap 保留供管理端）。refuseByToken 增 `lifecycleStatus=PENDING_CLIENT_SIGNATURE` 校验（已有）+ reason 必填（已有）+ 不触发 2.6（结构已保证，不注入上报服务）。签字成功 onClientSigned 只置 SIGNED+归档（不变）。

- [ ] **Step 4: 跑确认通过** Run: `mvn test -Dtest=ClientRecordGateTest,UnitRecordServiceTest,UnitRecordConfirmTest,UnitTokenScopeTest`，Expected: PASS。UnitTokenScopeTest 跨单位 getAs→1403 保持。

- [ ] **Step 5: 提交** `git commit -am "feat: 客户记录签字状态+最新版本门禁与字段白名单，拒签不触发2.6"`

---

### Task 5: 员工/组织/统计临时排除客户 + 菜单 badges 本单位收敛

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/AdminArchiveService.java`（employeeList L127）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/controller/UserController.java`（list L51）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/LedgerService.java`（orgTree L48）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/service/DataDomainService.java`（stats L125）
- Modify: `backend/src/main/java/com/cqwlw/maintenance/auth/MiniappMenuService.java`（badges L198-202）
- Test: 新增 `ClientExclusionTest.java`；`MiniappMenuServiceTest.java`（badges 本单位断言）

**Interfaces:**
- Consumes: T1 principalTypeOf；T3 白名单（客户令牌已拒这些端点，本任务防内部视图泄漏客户行 + badges 跨单位）。
- Produces: 员工列表/组织树/统计排除 CUSTOMER 主体；CLIENT badges.pendingSigns 按 useUnitId 过滤。

- [ ] **Step 1: 写失败测试**

```java
// employeeList / UserController.list 不含 CLIENT_* 行（种子含 CLIENT_ADMIN 员工）
assertTrue(service.employeeList().stream().noneMatch(m -> String.valueOf(m.get("role")).startsWith("CLIENT")));
// orgTree 不含 UNIT 部门节点（P1 临时过滤 department=UNIT 的 org）
// stats 不计客户（若含人数）；platformTotal 客户令牌路径已被 T3 拒，内部全量保持
// badges：CLIENT_ADMIN(uu_1) pendingSigns 只数本单位待签，不含 uu_2
```

- [ ] **Step 2: 跑确认失败** Run: `mvn test -Dtest=ClientExclusionTest`，Expected: FAIL。

- [ ] **Step 3: 实现** employeeList/UserController.list 过滤 `principalTypeOf(role)!=CUSTOMER`（多角色集任一内部即保留）；orgTree 过滤 UNIT 节点（P1 临时，P3 组织树重建）；MiniappMenuService.badges CLIENT 分支用注入的 scopeResolver/锚点取 useUnitId 后按单位 count（替换全表 count L198-202）。DataDomainService.stats 无人数则仅确认客户令牌不可达（T3 已拒），内部全量不变。

- [ ] **Step 4: 跑确认通过** Run: `mvn test -Dtest=ClientExclusionTest,MiniappMenuServiceTest,ScopeResolverEquivalenceTest`，Expected: PASS（ScopeResolverEquivalenceTest unitScope==null 口径不动——P1 不建 ScopeResolver 客户分支，单位收敛靠控制器锚点）。

- [ ] **Step 5: 提交** `git commit -am "feat: 员工/组织/统计临时排除客户主体，客户待签角标按本单位收敛"`

---

### Task 6: 客户工作台菜单收敛（下线报修/评价，三源同步）

**Files:**
- Modify: `backend/src/main/java/com/cqwlw/maintenance/auth/MiniappMenuService.java`（quickActions CLIENT L170-176、BUTTON_CODES L54）
- Modify: `mock/server.js`（/me/menus CLIENT quickActions L225-231、actions）
- Modify: `services/menu.js`（BUTTON_CODES L8-14 去 client:evaluate）
- Modify: `config/menus.js`（若 CLIENT tabs 调整；P1 tabs 保持 client-home/client-todo/client-elevators/profile）
- Modify: `docs/16-梯维通设计输入/routes-and-menus.md`（CLIENT quickActions 同步）
- Modify: `scripts/e2e/role-render.js`（账号 03/18 quickActions 期望去 report/emergency）
- Test: `backend/src/test/java/.../auth/MiniappMenuQuickActionsTest.java`（更新）

**Interfaces:**
- Consumes: T2 矩阵（client:report/evaluate DENY → actions 自动不含）；T5 badges。
- Produces: CLIENT quickActions 仅 elevators + todo（删 report 一键报修、emergency 急修进度）；actions 不含 client:evaluate/client:report。T8 小程序页面入口据此收敛。

**决策（我的调用）：** P1 客户 tabs 不变（待签/电梯/首页/我的），仅下线 quickActions 的报修/急修进度与评价按钮码；合同/移交客户本无 tab/qa 入口（profile 合同入口在 T8 处理）。

- [ ] **Step 1: 写失败测试**（MiniappMenuQuickActionsTest）

```java
// CLIENT quickActions 不含 report/emergency，含 elevators/todo，不指向 /pkg-maintenance/
List<Map<String,Object>> qa = menuService.menusOf(Set.of("CLIENT_ADMIN"), null, me).get("quickActions");
assertTrue(qa.stream().noneMatch(a -> "report".equals(a.get("key")) || "emergency".equals(a.get("key"))));
assertTrue(qa.stream().anyMatch(a -> "todo".equals(a.get("key"))));
// actions 不含 client:evaluate/client:report
assertFalse((Boolean) actions.get("client:evaluate"));
```

- [ ] **Step 2: 跑确认失败** Run: `mvn test -Dtest=MiniappMenuQuickActionsTest`，Expected: FAIL（原含 report/emergency）。

- [ ] **Step 3: 实现三源** Java quickActions CLIENT 分支删 report/emergency 两项（保留 elevators/todo）；BUTTON_CODES 删 client:evaluate（client:sign 保留）。mock /me/menus CLIENT 分支同删。services/menu.js BUTTON_CODES 去 client:evaluate。docs/16 CLIENT quickActions 块同步。config/menus.js tabs 不变（无需改）。

- [ ] **Step 4: 跑守卫与测试** Run: `mvn test -Dtest=MiniappMenuQuickActionsTest,MiniappMenuServiceTest`；`node scripts/verify-miniapp-routes.js`（tabs 双源绿）；`node scripts/verify-m1-sync.js`。Expected: PASS。

- [ ] **Step 5: 更新 role-render 基线** scripts/e2e/role-render.js 账号 03/18 的 quickActions 期望去 report/emergency（tabs 期望不变）。

- [ ] **Step 6: 提交** `git commit -am "feat: 客户工作台下线报修/评价入口，菜单三源同步"`

---

### Task 7: mock 客户路由安全加固 + loginPayload principalType

**Files:**
- Modify: `mock/server.js`（refuse-by-token L251、GET /unit/records/:id L640、pending L637、/unit/faults L912、/unit/elevators L898、sign-view L650-670、confirm-by-token L671-687、loginPayload L133-156）
- Modify: `mock/data.js`（如需补 lifecycleStatus/version 字段供门禁演示）
- Test: `scripts/verify-m1-sync.js`（契约三处）；新增 mock 契约断言（可并入 verify-review-fixes.js 或独立脚本）

**Interfaces:**
- Consumes: T2 矩阵（perms 收敛）、T4 白名单/门禁口径。
- Produces: mock 与后端 P1 门禁/白名单逐条对齐；loginPayload 增 `principalType`（EMPLOYEE/CUSTOMER，按 roles 判）。T8 小程序消费 principalType。

- [ ] **Step 1: 写失败断言**（mock 契约脚本）

```js
// GET /unit/records/:id（客户会话）返回不含 context/reportPayload/shareToken/originalRecordId/workerPlatformId/refuseReason
// 跨单位记录 id → 1403；非 PENDING_CLIENT_SIGNATURE 记录 confirm → 422
// refuse-by-token：token 不符→401，reason 空→422，非待签→422（不再是无校验 stub）
// /unit/records/pending、/unit/faults、/unit/elevators 按会话锚点本单位过滤（非 useUnits[0]/全量）
// sign-view 白名单含 recordVersion/hashPrefix
// loginPayload.principalType：CLIENT_ADMIN→CUSTOMER，MAINT_TECH→EMPLOYEE
```

- [ ] **Step 2: 跑确认失败** Run: `node scripts/verify-m1-sync.js` + 新断言脚本，Expected: FAIL。

- [ ] **Step 3: 实现** refuse-by-token 补 token/reason/状态校验；记录详情/列表/pending 加锚点校验 + toClientMap 等价白名单（删 context/reportPayload/shareToken 等）；faults/elevators 按 clientUnitAnchor 本单位过滤；sign-view 补 recordVersion/hashPrefix；confirm-by-token 加 lifecycleStatus=PENDING_CLIENT_SIGNATURE + confirmStatus=PENDING + 最新版本门禁；loginPayload 增 principalType（roles 全 CLIENT→CUSTOMER）。canonMap 第三份别名表改为引用根 ALIAS（消除重复维护，我的调用）。

- [ ] **Step 4: 跑确认通过** Run: `node scripts/verify-m1-sync.js`、`node scripts/verify-review-fixes.js` + 新断言，Expected: PASS。

- [ ] **Step 5: 提交** `git commit -am "feat: mock 客户路由门禁与字段白名单对齐后端，登录体增 principalType"`

---

### Task 8: 小程序客户页面入口收敛 + 守卫

**Files:**
- Modify: `pages/client/home/index.js`（onSignAlert L36-40 去 fault→evaluate 分支）
- Modify: `pages/profile/index.js`（buildAdminEntries L59-66 客户不显示 contract:view 入口）
- Modify: `pkg-client/pages/todo/index.js`（canSign 口径：CA+CS 均可签，对齐 T2 client:sign CA Y）
- Modify: `config/routes.js`（客户报修/评价路由 roles 收敛或标记默认关闭；handover detail 去 CLIENT 深链）
- Modify: `services/home.js`（getClientHome signAlerts 去评价提醒）
- Test: `scripts/e2e/role-render.js`（客户渲染面）；`scripts/verify-miniapp-routes.js`

**Interfaces:**
- Consumes: T6 菜单（quickActions 无报修/评价）、T7 loginPayload.principalType。
- Produces: 客户首页/我的/待签无报修/评价/合同入口；待签 CA+CS 均可签。

- [ ] **Step 1: 写失败断言**（role-render 增客户首页无评价入口；verify-miniapp-routes 路由角色收敛）

- [ ] **Step 2: 跑确认失败** Run: `node scripts/verify-miniapp-routes.js`，Expected: 相关检查 FAIL。

- [ ] **Step 3: 实现** home onSignAlert 仅 record→todo（删 fault→evaluate）；profile 客户主体（principalType=CUSTOMER 或 roles 全 CLIENT）不渲染 contract:view 管理入口；todo canSign 改 `roles 含 CLIENT_ADMIN 或 CLIENT_SIGNER`（对齐 client:sign 两角色 Y）；config/routes 报修/评价路由客户默认不可达（roles 去 CLIENT 或 perm 收敛）；getClientHome signAlerts 去评价提醒。评价/报修页面文件保留（P3 可能复用），仅断入口。

- [ ] **Step 4: 跑确认通过** Run: `node scripts/verify-miniapp-routes.js`、`node scripts/verify-miniapp-m0.js`、根 `npm run lint`，Expected: PASS。role-render 需 IDE WS 端口，若不可用则标注未跑（人工门禁）。

- [ ] **Step 5: 提交** `git commit -am "feat: 小程序客户页面下线报修/评价/合同入口，待签两角色可签"`

---

### Task 9: 全链路回归 + 文档同步

**Files:**
- Modify: `docs/04-接口文档.md`（A.9/A.12 登录体增 principalType；/unit/records 门禁/白名单/422 口径；追加修订记录）
- Modify: `docs/03-小程序页面结构.md`（客户工作台入口收敛；修订记录）
- Modify: `docs/12-未完成事项清单.md`（P1 落地状态；P2—P4 待办保留）
- Modify: `docs/14-账号权限与组织角色设计.md`（§12 P1 状态 🔲→✅；修订记录追加 P1 落地行）
- Modify: `AGENTS.md`（§10 追加 P1 修订记录；§3.9 口径补「客户主体路由白名单/签字门禁」，规则变更单列）

- [ ] **Step 1: 全量门禁** Run: backend `mvn clean test`（505+ 全绿）；admin `npm run lint && npm test && npm run build`；根 `npm run lint`；`node scripts/verify-dispatch.js`、`verify-templates.js`、`verify-miniapp-routes.js`、`verify-miniapp-m0.js`、`verify-m1-sync.js`、`verify-review-fixes.js`。Expected: 全绿。

- [ ] **Step 2: 小程序链路走查** mock 或本地后端走 客户登录→待签→签字（状态门禁）→拒签（原因必填）→记录详情（白名单）；内部员工登录→作业链路不变。automator 可用则跑 role-render.js + review-fixes-flow.js 客户相关项。

- [ ] **Step 3: 文档同步** 按 Files 清单更新五份文档 + 修订记录（AGENTS §8.3 每份加行）。docs/14 §12 P1 标 ✅，注明「P1 不改表/不动登录主体；P2—P4 待排期」。

- [ ] **Step 4: 提交** `git commit -am "docs: 权限P1落地回写文档与修订记录，全链路回归绿"`

---

## Self-Review 结论

- **Spec 覆盖**：§12 P1 九项逐条对应 T1（主体类型）/T2（矩阵收回+管理员关闭）/T3（路由白名单+旧CLIENT限/unit）/T4（签字门禁+白名单）/T5（员工组织统计排除）/T6+T8（工作台下线报修评价合同移交）。§4.6 门禁链→T4；§4.7 白名单→T4/T7；§5.4 能力码→T2；§7.3 路由边界→T3。
- **类型一致**：`principalTypeOf`/`PrincipalType`（T1）被 T3/T5/T7/T8 复用；`toClientMap`/`isLatestVersion`（T4）被 T7 对齐；`isCustomer`（T3）口径全计划一致（roles 非空且全 CUSTOMER）。
- **Review Focus 落点**：混合角色→T3 Step1；并发/旧版本→T4 Step1；字段泄漏→T4/T7 Step1；跨单位计数→T5/T7 Step1；镜像漂移→T2/T6 守卫。
- **比例**：计划以签名/断言/决策为主，未转录实现体；实现体由 implementer 按签名+测试推导。
- **未跑标注**：role-render/review-fixes automator 需 IDE WS 端口，不可用时标注人工门禁（AGENTS §7）。