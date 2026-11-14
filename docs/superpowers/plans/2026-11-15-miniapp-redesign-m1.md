# 博威无纸化小程序重构 M1 后端底座实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地单账号多角色后端底座：V41 账号统一、统一登录响应、/me/menus 菜单下发、V42 记录生命周期（签退即上报不变）、急修七态缺口端点、门户读端点改鉴权，mock/docs/04 三处同步。

**Architecture:** 在既有 M1—M5 权限底座（RoleCatalog 13 规范角色 / PermissionMatrix 86 码 / sys_user_role / MultiRoleGate / ScopeResolver / JWT sv+sid）之上增量扩展；门户账号（use_unit 表列，V34）并入 sys_employee+sys_user_role；记录生命周期叠加在 maintain_record（签退创建记录时初始化），2.6 上报时机不动。

**Tech Stack:** Java 17 / Spring Boot 3.3.4 / MyBatis-Plus / Flyway（只追加 V41—V43）/ JUnit5+Mockito / JWT(jjwt) / BCrypt。

**Spec:** `docs/superpowers/specs/2026-11-15-miniapp-redesign-design.md` §3/§4/§5；映射依据 `docs/superpowers/specs/2026-11-15-miniapp-redesign-m0-mappings.md` §1/§3/§5。

## Global Constraints

- 签退即冻结快照 + 2.6 即报（AGENTS §3.5）；归档/修订/拒签**不触发任何平台调用**；禁止自动重试（§2.4）。
- 合规主干逐字不动：签到 GPS/动态码原子消费/清单/30 分钟/各签各的/仅主维保签退。
- 迁移只追加（V41/V42/V43），不改已执行迁移；**严禁自动 TRUNCATE/DELETE**；旧表/旧列保留（留存）。
- openid 只信服务端 jscode2session；凭证只进环境变量；日志脱敏。
- 接口三处同步：services + mock/server.js + docs/04 A；方法签名 mock↔真实稳定。
- R7 跨部门禁止兼任（既有硬规则）；同部门多角色允许。
- 后端改业务规则必补测试；`mvn test` 全绿才提交；根 `npm run lint` 绿。
- 并行会话共享工作区：提交前 `git status` 核对，只 add 本任务文件，不触碰他人未提交编辑。

## File Structure

| 文件 | 职责 |
|---|---|
| `backend/src/main/resources/db/migration/V41__unit_account_unify.sql` | use_unit 门户账号并入 sys_employee+sys_user_role；account_migrated 标记 |
| `backend/src/main/resources/db/migration/V42__record_lifecycle.sql` | maintain_record 生命周期列 + sys_config record_review_enabled + 回填 |
| `backend/src/main/resources/db/migration/V43__fault_lifecycle.sql` | fault 取消/拒签/评价列 |
| `backend/.../auth/MiniappMenuService.java`（新） | ROLE_PRIORITY/ROLE_HOME/ROLE_TABS 服务端镜像 + /me/menus 投影 |
| `backend/.../auth/AuthController.java`（改） | loginResult 扩展 roles/perms/scope/activeWorkspace；/me/menus；门户分支降级为未迁移兜底 |
| `backend/.../service/RecordLifecycleService.java`（新） | 复核/发起签署/配合退回/拒签/归档投影 |
| `backend/.../service/UnitRecordService.java`（改） | confirm/confirmByToken 后 SIGNED→自动归档；pending/get/confirm 改统一令牌+CLIENT 谓词 |
| `backend/.../service/WorkOrderService.java`（改） | checkout 初始化 lifecycle_status |
| `backend/.../controller/DirectoryController.java`（改） | 急修 grab/arrive/submit/confirm/cancel/evaluate 端点 |
| `backend/.../service/DirectoryService.java`（改） | 急修七态流转 + SLA 升级 |
| `backend/.../entity/MaintainRecord.java` / `Fault.java`（改） | 新列字段 |
| `backend/src/test/.../auth/MiniappMenuServiceTest.java`（新） | 菜单投影/并集/workspace/漂移守卫 |
| `backend/src/test/.../service/UnitAccountUnifyTest.java`（新） | V41 合并规则 |
| `backend/src/test/.../service/RecordLifecycleTest.java`（新） | 生命周期+不重报 |
| `backend/src/test/.../service/FaultLifecycleTest.java`（新） | 七态+评价 |
| `services/menu.js`（新）/ `services/auth.js` / `order.js` / `fault.js` / `unit.js`（改） | 三处同步前端层 |
| `mock/server.js`（改） | +约 12 路由 |
| `docs/04-接口文档.md`（改） | A 部分新条目 + 版本行 |
| `scripts/verify-m1-sync.js`（新） | 三处同步集合守卫 |

**已核实事实（2026-11-15）**：登录响应现={token,userInfo,role}（AuthController:215-221）；JWT claims=role/client/sid/sv/openid（JwtService:45-62），门户 issueUnit ut=1（:68-81）；门户账号=use_unit 表列 account/password_hash/invite_code/openid/bound_at（V34）；sys_user_role(user_id,user_type EMPLOYEE/UNIT,role_code,org_id)（V35:19-29）；RoleCatalog 13 规范角色+别名 UNIT_ADMIN→CLIENT_ADMIN（RoleCatalog:75-100）；MaintainRecord.confirmStatus PENDING/CONFIRMED + shareToken（UnitRecordService:53-100）；fault 现有端点仅 POST/GET/GET{id}/PUT{id}/POST{id}/close（DirectoryController:76-114）；ScopeResolver 在 service 包；ATTR_ROLES=AuthInterceptor:24。

**裁决（预检）**：
- R1：生命周期列放 **maintain_record**（记录实体，签退创建），work_order 不动；confirmStatus 保留兼容旧客户端，CONFIRMED 回填 lifecycle=SIGNED。
- R2：V41 合并仅同部门（UNIT 或空）；**跨部门同账号不合并**（R7 硬规则），account_migrated=0 保留门户兜底登录至 M6/生产清库。代价：跨部门同账号边缘案例过渡期双入口。
- R3：V41 默认只授 CLIENT_ADMIN；CLIENT_SIGNER 须显式授予（蓝图「须显式持有」），演示账号张伟由 Seeder 授双角色。
- R4：/auth/login 门户分支保留为 account_migrated=0 兜底（@Deprecated 标注），正常路径统一；/unit/login* 同保留至 M6。

---

### Task 1: V41 账号统一迁移 + 合并规则测试

**Files:**
- Create: `backend/src/main/resources/db/migration/V41__unit_account_unify.sql`
- Create: `backend/src/test/java/com/cqwlw/maintenance/service/UnitAccountUnifyTest.java`
- Create: `backend/src/main/java/com/cqwlw/maintenance/service/UnitAccountUnifyService.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/entity/UseUnit.java`（+accountMigrated 字段）
- Read: `V34__use_unit_account.sql`、`V35__rbac_v2_schema.sql`、`V36__rbac_v2_backfill.sql`（回填模式参照）；核对 `entity/UseUnit.java` 与 `mapper/UseUnitMapper.java`、`mapper/UserRoleMapper.java` 存在（缺则按 MyBatis-Plus 惯例补建）

**Interfaces:**
- Produces: sys_employee 中 department=UNIT 的门户账号行 + sys_user_role CLIENT_ADMIN 行（org_id=use_unit.id）；use_unit.account_migrated 标记。Task 2/6 依赖。

- [ ] **Step 1: 写失败测试（合并规则）**

规则清单（逐条必须有对应测试方法）：① unit-only 新建 employee+CLIENT_ADMIN 角色行+migrated=1；② 同部门（UNIT/空）合并追加角色不新建行；③ 跨部门不合并 migrated=0（R7）；④ 合并不覆盖既有 password_hash；⑤ openid 已被占用不复制；⑥ 无 account 行零写入。

可执行示例（其余规则同风格补全；测试风格=纯 JUnit5+Mockito 构造注入，参照 UnitRecordServiceTest）：

```java
@Test
void crossDepartmentAccountIsNotMerged() {
    UseUnitMapper units = mock(UseUnitMapper.class);
    EmployeeMapper emps = mock(EmployeeMapper.class);
    UserRoleMapper roles = mock(UserRoleMapper.class);
    UseUnit u = new UseUnit();
    u.id = "u1"; u.account = "13800000000"; u.passwordHash = "h"; u.accountMigrated = 0;
    Employee maint = new Employee();
    maint.id = "e1"; maint.account = u.account; maint.department = "MAINT";
    when(units.selectList(any())).thenReturn(List.of(u));
    when(emps.selectOne(any())).thenReturn(maint);
    UnitAccountUnifyService svc = new UnitAccountUnifyService(units, emps, roles);

    assertEquals(0, svc.unifyPending());
    verify(emps, never()).insert(any());
    verify(roles, never()).insert(any());
    assertEquals(0, u.accountMigrated);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && mvn test -Dtest=UnitAccountUnifyTest`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现 V41 + UnitAccountUnifyService**

```sql
-- V41__unit_account_unify.sql
ALTER TABLE use_unit ADD COLUMN account_migrated TINYINT NOT NULL DEFAULT 0 COMMENT '门户账号并入 sys_employee 标记（M1）';
```

```java
// UnitAccountUnifyService.java（核心逻辑，逐规则对应测试）
@Service
public class UnitAccountUnifyService {
    // 依赖：UseUnitMapper / EmployeeMapper / UserRoleMapper（sys_user_role）
    @Transactional
    public int unifyPending() {
        int n = 0;
        for (UseUnit u : useUnitMapper.selectList(new LambdaQueryWrapper<UseUnit>()
                .isNotNull(UseUnit::getAccount).eq(UseUnit::getAccountMigrated, 0))) {
            Employee exist = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                    .eq(Employee::getAccount, u.account).last("LIMIT 1"));
            if (exist != null && exist.department != null
                    && !exist.department.equals("UNIT")) { continue; } // R7 跨部门不合并，保持 migrated=0
            Employee emp = exist;
            if (emp == null) {
                emp = new Employee();
                emp.id = Ids.next("emp");
                emp.account = u.account;
                emp.phone = u.account;
                emp.name = u.name == null ? u.account : u.name;
                emp.passwordHash = u.passwordHash;
                emp.department = "UNIT";
                emp.role = "CLIENT_ADMIN";
                emp.roleText = "使用单位管理员";
                emp.scopeVersion = 0;
                emp.enabled = true;
                if (u.openid != null && employeeMapper.selectCount(new LambdaQueryWrapper<Employee>()
                        .eq(Employee::getOpenid, u.openid)) == 0) { emp.openid = u.openid; }
                employeeMapper.insert(emp);
            }
            // 同部门追加角色（uk_user_role 幂等：先查后插）
            insertRoleIfAbsent(emp.id, "CLIENT_ADMIN", u.id);
            u.accountMigrated = 1;
            useUnitMapper.updateById(u);
            n++;
        }
        return n;
    }
}
```

- [ ] **Step 4: 跑测试确认通过 + 全量**

Run: `mvn test -Dtest=UnitAccountUnifyTest` → PASS；`mvn test` 全绿（基线 399+）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V41__unit_account_unify.sql backend/src/main/java/com/cqwlw/maintenance/service/UnitAccountUnifyService.java backend/src/test/java/com/cqwlw/maintenance/service/UnitAccountUnifyTest.java
git commit -m "feat: V41 门户账号并入员工体系（同部门合并/跨部门保留兜底/密码优先/openid 不抢夺）"
```

---

### Task 2: MiniappMenuService + GET /me/menus

**Files:**
- Create: `backend/src/main/java/com/cqwlw/maintenance/auth/MiniappMenuService.java`
- Create: `backend/src/test/java/com/cqwlw/maintenance/auth/MiniappMenuServiceTest.java`
- Modify: `backend/src/main/java/com/cqwlw/maintenance/auth/AuthController.java`（+/me/menus）

**Interfaces:**
- Produces: `MiniappMenuService.menusOf(Set<String> roles, String workspace)` → Map{user,home,tabs,quickActions,actions,badges}；`resolveHome(roles)`；`activeWorkspace(roles)`（ROLE_PRIORITY 首命中）；`ROLE_TABS` 常量。Task 3 login 扩展消费 resolveHome/activeWorkspace。

- [ ] **Step 1: 写失败测试**

规则清单：① 九角色 tabs 逐字等于蓝图 ROLE_TABS（L257-315，硬编码期望表）；② 多角色并集去重 ≤5；③ workspace 切换 home/tabs；④ actions=PermissionMatrix 并集；⑤ badges 键存在；⑥ 扩展角色只读投影。

可执行示例：

```java
@Test
void maintTechTabsMatchBlueprintVerbatim() {
    MiniappMenuService svc = new MiniappMenuService(mock(FaultMapper.class),
            mock(MaintainRecordMapper.class), mock(ScopeResolver.class));
    Map<String, Object> out = svc.menusOf(Set.of("MAINT_TECH"), null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tabs = (List<Map<String, Object>>) out.get("tabs");
    assertEquals(List.of("maint-home", "maint-orders", "maint-elevators", "profile"),
            tabs.stream().map(t -> t.get("key")).collect(Collectors.toList()));
    assertEquals("/pages/maint/home/index", out.get("home"));
    assertEquals("MAINT_TECH", out.get("activeWorkspace"));
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=MiniappMenuServiceTest` → FAIL（类不存在）。

- [ ] **Step 3: 实现 MiniappMenuService + 端点**

ROLE_PRIORITY = [MAINT_MGR, MAINT_SUP, MAINT_TECH, INST_MGR, INST_PM, INST_CLERK, CLIENT_ADMIN, CLIENT_SIGNER, PLATFORM_ADMIN]；ROLE_HOME/ROLE_TABS 逐字镜像蓝图（含 INST_MGR/MAINT_SUP/MAINT_MGR 5 项）；扩展角色映射：MAINT_QUALITY/MAINT_BIZ→MAINT_SUP 表（前端按 actions 隐藏写按钮）、INSTALLER→INST_CLERK 表。actions = 对按钮码集合逐码 `roles.stream().anyMatch(r -> PermissionMatrix.allows(r, code))`。badges：pendingOrders=fault status OPEN 且 ScopeResolver 可见；pendingSigns=maintain_record lifecycle IN (PENDING_CLIENT_SIGN, PENDING_CLIENT_SIGNATURE) 且 CLIENT 范围。

```java
// AuthController 追加
@GetMapping("/me/menus")
public ApiResponse<Map<String, Object>> meMenus(HttpServletRequest request,
                                                @RequestParam(required = false) String workspace) {
    Employee user = currentUser(request);
    if (user == null) throw new BizException(401, "登录已过期，请重新登录");
    @SuppressWarnings("unchecked")
    Set<String> roles = (Set<String>) request.getAttribute(AuthInterceptor.ATTR_ROLES);
    return ApiResponse.ok(miniappMenuService.menusOf(roles, workspace));
}
```

- [ ] **Step 4: 跑测试通过 + 全量**

Run: `mvn test -Dtest=MiniappMenuServiceTest` → PASS；`mvn test` 全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/auth/MiniappMenuService.java backend/src/test/java/com/cqwlw/maintenance/auth/MiniappMenuServiceTest.java backend/src/main/java/com/cqwlw/maintenance/auth/AuthController.java
git commit -m "feat: GET /me/menus 小程序菜单下发（ROLE_TABS 服务端镜像+多角色并集+workspace 切换+actions/badges）"
```

---

### Task 3: 统一登录响应扩展 + 门户分支降级

**Files:**
- Modify: `AuthController.java`（loginResult/unitLoginResult）、`UnitAccountService.java`（@Deprecated 标注）
- Create: `backend/src/main/java/com/cqwlw/maintenance/auth/UserRoleService.java`（canonicalRolesOf：sys_user_role ∪ 别名归一后的旧主角色）
- Modify: `backend/src/test/java/com/cqwlw/maintenance/auth/UnifiedLoginTest.java`（扩展）

**Interfaces:**
- Consumes: Task 1 合并行、Task 2 resolveHome。
- Produces: /auth/login 响应 {token, userInfo, role, roles[], perms[], scope, activeWorkspace, home}；旧字段保留（兼容）。

- [ ] **Step 1: 扩展失败测试**

规则清单：① roles=规范码并集（含别名归一）；② CLIENT 登录 activeWorkspace/home 正确且无 portal 标记；③ 未迁移门户账号仍 portal=true 兜底（R4）；④ 旧字段 token/userInfo/role 不变；⑤ scope 一句话投影。

可执行示例（UserRoleService 纯逻辑）：

```java
@Test
void canonicalRolesUnionIncludesAliasedLegacyPrimary() {
    UserRoleMapper mapper = mock(UserRoleMapper.class);
    UserRole row = new UserRole();
    row.userId = "e9"; row.roleCode = "CLIENT_SIGNER"; row.userType = "EMPLOYEE";
    when(mapper.selectList(any())).thenReturn(List.of(row));
    UserRoleService svc = new UserRoleService(mapper);

    Set<String> roles = svc.canonicalRolesOf("e9", "UNIT_ADMIN");

    assertEquals(Set.of("CLIENT_ADMIN", "CLIENT_SIGNER"), roles); // UNIT_ADMIN 别名→CLIENT_ADMIN
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=UnifiedLoginTest` → 新断言 FAIL。

- [ ] **Step 3: 实现 loginResult 扩展**

```java
private Map<String, Object> loginResult(Employee user, String openid, String client) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("token", issueSessionToken(user, user.role, openid, client));
    out.put("userInfo", userInfo(user));
    out.put("role", user.role);
    Set<String> roles = userRoleService.canonicalRolesOf(user.id, user.role); // sys_user_role ∪ {canonical(user.role)}
    out.put("roles", new ArrayList<>(roles));
    out.put("perms", PermissionMatrix.unionPerms(roles));   // 新增静态方法：CAPABILITIES 逐码 anyMatch allows
    out.put("scope", scopeResolver.describe(user, roles));  // 新增：部门+可见范围一句话
    String ws = MiniappMenuService.activeWorkspace(roles);  // ROLE_PRIORITY 首命中
    out.put("activeWorkspace", ws);
    out.put("home", MiniappMenuService.resolveHome(roles));
    return out;
}
```

unitLoginResult 加 @Deprecated 注释（R4 兜底）；/unit/login、/unit/login-by-wechat、/unit/account/password 同标注（M6 删除）。

- [ ] **Step 4: 跑测试通过 + 全量**

Run: `mvn test -Dtest=UnifiedLoginTest` → PASS；`mvn test` 全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/auth/AuthController.java backend/src/main/java/com/cqwlw/maintenance/auth/PermissionMatrix.java backend/src/main/java/com/cqwlw/maintenance/service/ScopeResolver.java backend/src/test/java/com/cqwlw/maintenance/auth/UnifiedLoginTest.java
git commit -m "feat: 统一登录响应扩展 roles/perms/scope/activeWorkspace/home（门户分支降级未迁移兜底）"
```

---

### Task 4: V42 记录生命周期 + 端点 + 自动归档

**Files:**
- Create: `V42__record_lifecycle.sql`、`RecordLifecycleService.java`、`RecordLifecycleTest.java`
- Modify: `MaintainRecord.java`、`WorkOrderService.java`（checkout 初始化）、`UnitRecordService.java`（confirm 后归档）、`WorkOrderController.java`（+4 端点）、`UnitRecordController.java`（+refuse-by-token）

**Interfaces:**
- Produces: lifecycle_status ∈ {PENDING_REVIEW, PENDING_CLIENT_SIGN, PENDING_CLIENT_SIGNATURE, SIGNED, ARCHIVED, REFUSED_RETURNED}；端点 POST /work-orders/{id}/signature-refuse、/review、/sign-request、GET /work-orders/{id}/archive、POST /unit/records/{id}/refuse-by-token。

- [ ] **Step 1: 写失败测试**

规则清单：① checkout 初始化按 record_review_enabled 分流；② 复核通过→PENDING_CLIENT_SIGN、退回→作业中（lifecycle 清空+work_order PROCESSING）；③ signRequest 双签不齐 422；④ signature-refuse 仅配合人本人、清签字、无上报副作用；⑤ confirmByToken→SIGNED→ARCHIVED（hash 非空、reportStatus 不变）；⑥ refuse-by-token 原因必填 422；⑦ archive 投影非 ARCHIVED 422；⑧ 越权 1403 无副作用。

可执行示例：

```java
@Test
void clientSignArchivesWithoutReReport() {
    MaintainRecordMapper records = mock(MaintainRecordMapper.class);
    RecordPdfService pdf = mock(RecordPdfService.class);
    FileStorageService files = mock(FileStorageService.class);
    when(pdf.generateArchivePdf(any())).thenReturn(new byte[]{1, 2, 3});
    when(files.store(any(), any())).thenReturn("file_arch");
    RecordLifecycleService svc = new RecordLifecycleService(records,
            mock(WorkOrderMapper.class), pdf, files, mock(SysConfigService.class));
    MaintainRecord r = new MaintainRecord();
    r.id = "r1"; r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE"; r.reportStatus = "SUCCESS";
    when(records.selectById("r1")).thenReturn(r);

    svc.onClientSigned(r);

    assertEquals("ARCHIVED", r.lifecycleStatus);
    assertNotNull(r.archiveHash);
    assertEquals("file_arch", r.archiveFileId);
    assertEquals("SUCCESS", r.reportStatus); // 2.6 不重报（D2）
}
```

注意：RecordLifecycleService 构造器**不注入任何平台上报服务**（结构上保证归档不触发平台调用）；sys_config 列名以 `V39__m5_data_domain.sql` 实测为准调整 INSERT 与 SysConfigService 读取。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=RecordLifecycleTest` → FAIL。

- [ ] **Step 3: 实现 V42 + 服务 + 端点**

```sql
-- V42__record_lifecycle.sql
ALTER TABLE maintain_record
  ADD COLUMN lifecycle_status VARCHAR(32) NULL COMMENT '记录生命周期（M1，蓝图 §6.2）',
  ADD COLUMN refuse_reason VARCHAR(500) NULL,
  ADD COLUMN archived_at DATETIME NULL,
  ADD COLUMN archive_file_id VARCHAR(32) NULL,
  ADD COLUMN archive_hash VARCHAR(64) NULL;
UPDATE maintain_record SET lifecycle_status = 'SIGNED' WHERE confirm_status = 'CONFIRMED' AND lifecycle_status IS NULL;
UPDATE maintain_record SET lifecycle_status = 'PENDING_CLIENT_SIGN' WHERE confirm_status = 'PENDING' AND lifecycle_status IS NULL;
INSERT INTO sys_config (config_key, config_value, remark) VALUES ('record_review_enabled', '0', '维保记录复核开关（M1）')
  ON DUPLICATE KEY UPDATE config_key = config_key;
```

RecordLifecycleService：review/signRequest/signatureRefuse/refuseByToken/archiveView，逐条对应测试；归档调 RecordPdfService 生成 PDF → FileStorageService 存储 → SHA-256 → 写 archive_* ；**不调 PlatformReportService**。UnitRecordService.confirm/confirmByToken 末尾调 `recordLifecycleService.onClientSigned(r)`。WorkOrderService.checkout 创建记录处设 lifecycle_status（读 sys_config）。

- [ ] **Step 4: 跑测试通过 + 全量 + 回归**

Run: `mvn test -Dtest=RecordLifecycleTest` → PASS；`mvn test` 全绿；`node scripts/verify-dispatch.js && node scripts/verify-templates.js` 绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V42__record_lifecycle.sql backend/src/main/java/com/cqwlw/maintenance/service/RecordLifecycleService.java backend/src/main/java/com/cqwlw/maintenance/entity/MaintainRecord.java backend/src/main/java/com/cqwlw/maintenance/service/WorkOrderService.java backend/src/main/java/com/cqwlw/maintenance/service/UnitRecordService.java backend/src/main/java/com/cqwlw/maintenance/controller/WorkOrderController.java backend/src/main/java/com/cqwlw/maintenance/controller/UnitRecordController.java backend/src/test/java/com/cqwlw/maintenance/service/RecordLifecycleTest.java
git commit -m "feat: V42 记录生命周期（复核可配/发起签署/配合退回/拒签/自动归档 SHA-256，不重报 2.6）"
```

---

### Task 5: 急修七态缺口端点 + 评价

**Files:**
- Create: `V43__fault_lifecycle.sql`、`FaultLifecycleTest.java`
- Modify: `Fault.java`、`DirectoryController.java`、`DirectoryService.java`

**Interfaces:**
- Produces: POST /faults/{id}/{grab,arrive,submit,confirm,cancel,evaluate}；状态 OPEN→ASSIGNED→PROCESSING→PENDING_CONFIRM→CLOSED/REFUSED/CANCELLED。

- [ ] **Step 1: 写失败测试**

规则清单：① grab 区域校验超范围 1403；② arrive GPS 缺失 422、显式打卡优先；③ submit 原因/措施必填；④ confirm 仅 CLIENT_SIGNER（管理员须兼任）；⑤ cancel 角色+原因；⑥ evaluate 仅 CLOSED、1-5 星、重复 422；⑦ refuse 原因必填；⑧ 派单端点核对（DirectoryService.dispatchFault 已有，验证控制器路由+MultiRoleGate 放行）。

可执行示例：

```java
@Test
void evaluateRejectedBeforeClosed() {
    FaultMapper faults = mock(FaultMapper.class);
    Fault f = new Fault();
    f.id = "f1"; f.status = "PROCESSING";
    when(faults.selectById("f1")).thenReturn(f);
    DirectoryService svc = new DirectoryService(faults /* 其余依赖按现有构造器补 mock */);

    BizException ex = assertThrows(BizException.class,
            () -> svc.evaluate("f1", Map.of("score", 5), Set.of("CLIENT_ADMIN"), "u1"));

    assertEquals(422, ex.code);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=FaultLifecycleTest` → FAIL。

- [ ] **Step 3: 实现 V43 + 端点**

```sql
-- V43__fault_lifecycle.sql
ALTER TABLE fault
  ADD COLUMN cancel_reason VARCHAR(500) NULL,
  ADD COLUMN refuse_reason VARCHAR(500) NULL,
  ADD COLUMN evaluate_score TINYINT NULL,
  ADD COLUMN evaluate_text VARCHAR(500) NULL,
  ADD COLUMN evaluated_at DATETIME NULL;
```

DirectoryService 逐态流转方法（归属谓词复用 V7/ScopeResolver）；DirectoryController 六端点 + @RequirePerm(order:grab/order:arrive/order:submit/order:confirm/order:cancel/client:evaluate)；SLA 升级：既有 deadline 字段 + 定时任务扫描超时→通知（order:escalate 系统，无 UI）。

- [ ] **Step 4: 跑测试通过 + 全量**

Run: `mvn test -Dtest=FaultLifecycleTest` → PASS；`mvn test` 全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V43__fault_lifecycle.sql backend/src/main/java/com/cqwlw/maintenance/entity/Fault.java backend/src/main/java/com/cqwlw/maintenance/controller/DirectoryController.java backend/src/main/java/com/cqwlw/maintenance/service/DirectoryService.java backend/src/test/java/com/cqwlw/maintenance/service/FaultLifecycleTest.java
git commit -m "feat: 急修七态缺口端点（grab/arrive/submit/confirm/cancel/evaluate+SLA 升级）"
```

---

### Task 6: 门户读端点改统一令牌 + CLIENT 谓词

**Files:**
- Modify: `UnitRecordService.java`（pending/get/confirm 鉴权）、`WebConfig.java`（白名单核对）、`UnitTokenScopeTest.java`（扩展）

**Interfaces:**
- Consumes: Task 1 合并行、ScopeResolver ORG_FLAT。
- Produces: /unit/records/pending、/{id}、/confirm 接受统一员工令牌（CLIENT 角色）+ 本单位谓词；ut=1 旧令牌仅未迁移兜底。

- [ ] **Step 1: 扩展失败测试**

规则清单：① 统一 CLIENT_ADMIN 令牌 pending 仅本单位；② MAINT_TECH 令牌门户读 1403；③ 旧 ut=1 未迁移兜底仍可用；④ confirm 跨单位 1403 无副作用。

可执行示例：

```java
@Test
void maintTokenCannotReadPortalRecords() {
    MaintainRecordMapper records = mock(MaintainRecordMapper.class);
    UnitRecordService svc = new UnitRecordService(records,
            mock(WorkOrderService.class), mock(FileStorageService.class));

    BizException ex = assertThrows(BizException.class,
            () -> svc.pendingAs(Map.of(), Set.of("MAINT_TECH"), null));

    assertEquals(1403, ex.code);
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -Dtest=UnitTokenScopeTest` → 新断言 FAIL。

- [ ] **Step 3: 实现鉴权迁移**

UnitRecordService 增加 `pendingAs(query, roles, unitId)` / `getAs(id, roles, unitId)` / `confirmAs(id, body, roles, unitId)` 重载：无 CLIENT_* 角色→1403；有→use_unit_id 谓词过滤；控制器从 ATTR_ROLES + 主体 unit 锚点（sys_user_role.org_id）传参。旧 pending/get/confirm 保留为 ut=1 门户路径（@Deprecated）。WebConfig 白名单仅 sign-view/confirm-by-token/refuse-by-token 免登录，其余需 Bearer。

- [ ] **Step 4: 跑测试通过 + 全量**

Run: `mvn test -Dtest=UnitTokenScopeTest` → PASS；`mvn test` 全绿。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/service/UnitRecordService.java backend/src/main/java/com/cqwlw/maintenance/config/WebConfig.java backend/src/test/java/com/cqwlw/maintenance/auth/UnitTokenScopeTest.java
git commit -m "feat: 门户读端点改统一令牌+CLIENT 本单位谓词（旧门户令牌仅未迁移兜底）"
```

---

### Task 7: 三处同步 + 门禁 + 回写

**Files:**
- Create: `services/menu.js`、`scripts/verify-m1-sync.js`
- Modify: `services/auth.js`、`services/order.js`、`services/fault.js`、`services/unit.js`、`mock/server.js`、`docs/04-接口文档.md`、`docs/12-未完成事项清单.md`

**Interfaces:**
- Consumes: Task 2–6 全部端点。
- Produces: 三处同步闭合 + M1 门禁证据。

- [ ] **Step 1: services 层同步**

menu.js：`getMenus(workspace)` → GET /me/menus。order.js：+signatureRefuse/review/signRequest/getArchive。fault.js：+grab/arrive/submit/confirm/cancel/evaluate。unit.js：portalGet 改统一令牌（保留 unit_token 兜底分支）。auth.js：applyLoginResult 透传 roles/perms/activeWorkspace/home。

- [ ] **Step 2: mock 同步（约 12 路由）**

mock/server.js：/me/menus（按 currentRole 投影 ROLE_TABS 镜像）、/work-orders/:id/signature-refuse|review|sign-request|archive、/unit/records/:id/refuse-by-token、/faults/:id/grab|arrive|submit|confirm|cancel|evaluate；登录 mock 响应补 roles/perms/activeWorkspace/home。

- [ ] **Step 3: docs/04 A 部分条目 + 版本行**

逐端点登记（方法/路径/请求/响应/错误码/权限码），版本 V2.53→V2.54（以当前实际版本+1）。

- [ ] **Step 4: 三处同步守卫**

```js
// scripts/verify-m1-sync.js：M1 新端点清单（硬编码 12+6 路径）逐条断言出现在
// services/*.js（方法体路径串）、mock/server.js（路由模板）、docs/04（A 部分条目）；缺口退出码 1，JSON 计数输出
```

Run: `node scripts/verify-m1-sync.js` → 全 0 缺口。

- [ ] **Step 5: 全量门禁 + 回写 + 提交**

Run: `cd backend && mvn test`（全绿）；根 `npm run lint`；`node scripts/verify-dispatch.js && node scripts/verify-templates.js`；`node scripts/verify-miniapp-m0.js`（M0 守卫不回归）。
docs/12 H 组 M1 行标 ✅（证据：单测数/门禁/三处同步计数）+ 修订行。

```bash
git add services/menu.js services/auth.js services/order.js services/fault.js services/unit.js mock/server.js docs/04-接口文档.md docs/12-未完成事项清单.md scripts/verify-m1-sync.js
git commit -m "feat: M1 三处同步闭合（services+mock+docs/04）与门禁回写"
```

- [ ] **Step 6: 呈交用户审阅 M1，协调 master 整合后进入 M2**