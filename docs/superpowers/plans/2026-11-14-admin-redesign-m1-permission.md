# M1 后端权限底座 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把后端鉴权层从「12 角色单列 + 24 粗粒度二值码 + console 两档门禁」升级为「13 规范角色多行 + 86 三态码 + 6 范围读写双谓词 + 限时授权 + 业务审计」底座，且旧端点行为逐条不变。

**Architecture:** 方案 1（单一新矩阵 + 旧码翻译适配）。`PermissionMatrix` 新增 86 码三态矩阵作为新事实源，24 旧码保留为冻结兼容别名（`RbacMatrixTest` 锁定其精确授权）；`LegacyCapabilityTranslator` 承载 M0 §2.1 的 24→71 映射与 adminConsole 门控、order/fault 语义反转。`AuthInterceptor` 增 scopeVersion 校验与多角色集 `ATTR_ROLES`，`AdminRoleInterceptor` 字面不动。`ScopeResolver` 以委托方式复现 V7 谓词并证等价。仅动 backend 鉴权层，业务查询/admin/小程序零改动。

**Tech Stack:** Java 17 / Spring Boot 3.3.4 / MyBatis-Plus / Flyway（只追加，下一号 V35）/ JUnit 5 + Mockito / JWT（jjwt）/ Lombok @Data。

**Spec:** `docs/superpowers/specs/2026-11-14-admin-redesign-m1-permission-design.md`（V1.0，已评审提交 0c7da8a）。外部基准 `docs/16-梯维通设计输入/roles-permissions.md` V1.3（71 码）+ `docs/superpowers/specs/2026-11-14-admin-redesign-m0-mappings.md` V1.5（表 2 权限码映射）。

## Global Constraints

以下约束逐字来自 spec 与 AGENTS.md，每个任务的要求都隐含包含本节：

- **范围（spec §1.3 反目标）**：不接业务视图、不改业务查询；不改 admin / 小程序代码；不实现 C 态「需审批」工作流（M5）；不改 `sys_employee.role` 旧码值（M5）；不实现邀请码/系统配置/敏感导出审批/电梯双人确认（M3/M4/M5）；不做任何自动 TRUNCATE/DELETE 数据迁移；不触派单不变量（AGENTS §3.6）；不引入上报自动重试（AGENTS §2.4）。
- **建表约定（spec §2）**：`VARCHAR(32)` 字符串主键 + `VARCHAR(16)` 字符串枚举 + `DATETIME` + `KEY idx_` + `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` + 列 COMMENT。**不套用** bigint/tinyint/deleted/create_by 通用模板。主键经 `Ids.next(prefix)`，时间经 `TimeUtil.now()`（zone Asia/Shanghai，格式 `yyyy-MM-dd HH:mm:ss`）。
- **Flyway（AGENTS §4.2 / spec §6.3）**：只追加新版本，不得修改已执行迁移；下一号 V35（V30 已重编 V34，V32 预留）；打包必须 `mvn clean package`；`MigrationIntegrityTest` 守同版本重复。
- **兼容硬门禁（spec §7.1）**：`RbacMatrixTest` 与 `AdminRoleInterceptorCompatTest` **字面不动且保持绿**。`codes()` 必须仍返回 12 旧码、`capabilities()` 必须仍返回 24 旧码、`allows(role,旧码)` 必须复现精确二值授权。新规范码走**独立**方法/常量。
- **读写分离红线（spec §4.4 / AGENTS §3.1）**：范围扩大（区域、部门全量）只进读谓词；写谓词严格本人/本组/被指派，防代签到代签退。越权一律 `1403`/`403` 且不得产生副作用。
- **order/fault 语义反转（M0 §2.0，Blocker）**：旧 `order:*`=维保工单 → 新 `plan/task/checklist/record`；旧 `fault:*`=急修单 → 新 `order:*`。禁止字面同名映射。
- **旧 token 兼容（spec §4.2）**：pre-M1 token 无 `sv` claim → `sv=null→0`，与默认 `scope_version=0` 匹配，不误拒。门户 `UNIT_ADMIN` 别名放行。
- **安全红线（AGENTS §2）**：凭证 `REG_*`/`WX_APPSECRET`/`JWT_SECRET`/`COS_*`/LBS key 严禁入库；openid 只信服务端换取；`JWT_SECRET` 无默认值缺失即启动失败；文件访问带时效签名 `?s=`；日志脱敏（不打印完整 token/手机号/openid/平台密钥）；敏感文件不入库。
- **单身份登录（AGENTS §3.9）**：员工态与门户态互斥，本计划不改该不变量。
- **生产清库（spec §6.3）**：一次性人工上线步骤（备份→用户确认→清业务数据保 Flyway schema），**不在任何迁移里**。
- **编码约定（AGENTS §4.2）**：单测与实现同包路径；改业务规则必须补测试；统一 `ApiResponse` 返回；写操作先校验再落库。
- **门禁命令（spec §7.3 / AGENTS §4.4）**：`backend/` `mvn test` 全绿（322 基线不回退 + 新增）；`node scripts/verify-dispatch.js`、`node scripts/verify-templates.js`；`admin/` `npm run lint && npm test && npm run build`（M1 不改 admin，跑一遍确认零回归）。
- **提交（AGENTS §6）**：一行中文动词开头；禁止凭证/token/报文原文；禁止未经确认的破坏性操作。

## 锁定事实（实现前必读，避免破坏绿测试）

下列构造器/断言被现有测试锁定，**不得改变其签名或行为**：

| 文件 | 锁定点 | 本计划处置 |
|---|---|---|
| `auth/RbacMatrixTest.java` | `codes()`==12 旧码；`capabilities()`==24 旧码；`allows(role,旧码)` 精确二值；`canUseAdminConsole(WORKER/ASSISTANT/INSTALL_WORKER/UNIT_ADMIN)`==false | 字面不动；新码走独立方法 |
| `auth/AdminRoleInterceptorCompatTest.java:69` | `new AdminRoleInterceptor()`（无参）；5 角色×17 路由旧两档判定 | `AdminRoleInterceptor` 字面不动 |
| `AdminRoleInterceptorTest.java:24` | `new AdminRoleInterceptor()`（无参） | 同上 |
| `auth/AdminRoleInterceptorCompatTest.java:142` | `new WebConfig(auth, admin, audit)`（3 参） | `WebConfig` 不动 |
| `auth/UnitTokenScopeTest.java:33` | `new AuthInterceptor(jwt, mock(EmployeeMapper))`（2 参）；`ATTR_ROLE`=="UNIT_ADMIN" | 保留 2 参构造器（委托新 3 参） |
| `AuthInterceptorTest.java:32` | `new AuthInterceptor(jwtService, employeeMapper)`（2 参） | 同上 |
| `AuditInterceptorTest.java:30` | `new AuditInterceptor(opLogMapper)`（1 参） | `AuditInterceptor` 不动 |
| `auth/UnifiedLoginTest.java:32` | `new AuthController(employeeMapper, jwt, wx, menu, unitAccount)`（5 参）；`jwt.issue(5 参)` mock | 构造器不变；`issue` 改 6 参须同步更新该 mock |
| `DemoDataSeederTest.java:72` | `new DemoDataSeeder(props, CompanyMapper, employeeMapper, UseUnitMapper, elevatorMapper, orderMapper, recordMapper, MessageMapper, RescueMapper, FaultMapper, DrillMapper, InspectRecordMapper, KnowledgeMapper, checklistService, WorkOrderService)`（15 参） | 增 2 mapper → 17 参，同步更新该测试构造调用 |
| `common/GlobalExceptionHandler.java` | 无构造器依赖、无测试 | 可增 `AuditLogService` 依赖 + BLOCKED 钩子 |

**关键语义裁决**：M1 保留旧行为，目标态差异推迟 M5。新三态矩阵（`STATES`）按 roles-permissions.md §4 目标态编码（如 `record:view` 不含 CLIENT_ADMIN）；旧别名 `allows(role,旧码)` 走**冻结** `GRANTS`（如 `record:read` 仍含 UNIT_ADMIN）。两者并存于 `PermissionMatrix` 一个模块＝单一事实源（新码看 STATES，旧别名看冻结 GRANTS），翻译器是桥。`canUseAdminConsole(UNIT_ADMIN/CLIENT_ADMIN)` 在 M1 保持 **false**（CA 成为 Web 可登录是 M5 新能力）。

## File Structure

**新建（backend/src/main/java/com/cqwlw/maintenance/）**
- `entity/SysOrg.java` `entity/SysUserRole.java` `entity/SysRoleScope.java` `entity/SysGrant.java` `entity/SysAuditLog.java` — 五表实体（@Data @TableName + @TableId public String id + public 字段 + LocalDateTime）。
- `mapper/SysOrgMapper.java` `mapper/SysUserRoleMapper.java` `mapper/SysRoleScopeMapper.java` `mapper/SysGrantMapper.java` `mapper/SysAuditLogMapper.java` — `@Mapper extends BaseMapper<X>`。
- `auth/LegacyCapabilityTranslator.java` — 24→71 映射 + adminConsole 门控 + order/fault 反转 + 旧别名二值判定。
- `service/ScopeResolver.java` — 6 范围读写双谓词门面（委托 EmployeeScopeService/DeptScopeService + 新角色 sys_role_scope）。
- `service/GrantService.java` — 限时授权生命周期 + @Scheduled 回收 + 解析并集。
- `service/AuditLogService.java` — 业务审计 append + query（无删改）。
- `resources/db/migration/V35__rbac_v2_schema.sql` `V36__rbac_v2_backfill.sql`。

**修改**
- `entity/Employee.java` — 增 `public Integer scopeVersion;`。
- `mapper/EmployeeMapper.java` — 增 `bumpScopeVersion(@Param("id"))` 显式 SQL。
- `auth/RoleCatalog.java` — v2 别名表 + 13 规范角色 + canonical 方法（保留 codes()==12 旧码）。
- `auth/PermissionMatrix.java` — 增 TriState/STATES/state()/allowsNew()/canonicalCapabilities()（保留 24 旧常量/GRANTS/allows/capabilities）。
- `auth/JwtService.java` — `issue` 增第 6 参 scopeVersion + `sv` claim。
- `auth/AuthController.java` — `issueSessionToken` 传 `user.scopeVersion`。
- `auth/AuthInterceptor.java` — sv 校验（null 安全）+ ATTR_ROLES + 3 参 @Autowired 构造器（2 参委托）+ 门户 CLIENT_ADMIN 别名。
- `common/GlobalExceptionHandler.java` — 增 AuditLogService 依赖 + 403/1403 BLOCKED 审计钩子。
- `service/DemoDataSeeder.java` — 增 SysOrgMapper/SysUserRoleMapper + 张工 INST_MGR + org/user_role 演示种子。

**新建测试（backend/src/test/java/com/cqwlw/maintenance/）**
- `RbacV2MigrationTest.java` `entity/RbacEntityMappingTest.java` `auth/RoleCatalogV2Test.java` `auth/PermissionMatrixV2Test.java` `auth/LegacyCapabilityTranslatorTest.java` `auth/JwtServiceSvTest.java` `auth/AuthInterceptorV2Test.java` `service/ScopeResolverEquivalenceTest.java` `service/GrantServiceTest.java` `service/AuditLogServiceTest.java` `common/GlobalExceptionHandlerAuditTest.java`。

**修改测试**
- `auth/UnifiedLoginTest.java`（issue 6 参 mock）、`DemoDataSeederTest.java`（构造器 +2 mock + 张工断言）。

**不动（保持绿）**：`AdminRoleInterceptor.java` `AuditInterceptor.java` `MenuService.java` `AdminRoles.java` `WebConfig.java` `EmployeeScopeService.java` `DeptScopeService.java` `RbacMatrixTest.java` `AdminRoleInterceptorCompatTest.java` `AdminRoleInterceptorTest.java` `AuthInterceptorTest.java` `UnitTokenScopeTest.java` `AuditInterceptorTest.java` `MigrationIntegrityTest.java`。

---

### Task 1: V35 schema 迁移（五表 + scope_version + 角色范围种子）

**Files:**
- Create: `backend/src/main/resources/db/migration/V35__rbac_v2_schema.sql`
- Test: `backend/src/test/java/com/cqwlw/maintenance/RbacV2MigrationTest.java`

**Interfaces:**
- Consumes: 无（首个任务）。
- Produces: 表 `sys_org`/`sys_user_role`/`sys_role_scope`/`sys_grant`/`sys_audit_log`；列 `sys_employee.scope_version`；`sys_role_scope` 13 行种子。后续实体/mapper 任务映射这些表。

- [ ] **Step 1: Write the failing test**

`RbacV2MigrationTest.java`（无测试数据库，按 classpath 资源文本断言结构不变量——spec §7.2「迁移干净应用」口径）：

```java
package com.cqwlw.maintenance;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V35/V36 结构不变量（无测试库，按 classpath SQL 文本断言；spec §7.2）。 */
class RbacV2MigrationTest {

    private String sql(String name) throws Exception {
        return new String(new ClassPathResource("db/migration/" + name).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void v35CreatesFiveTablesAndScopeVersion() throws Exception {
        String s = sql("V35__rbac_v2_schema.sql");
        for (String t : new String[]{"sys_org", "sys_user_role", "sys_role_scope", "sys_grant", "sys_audit_log"}) {
            assertTrue(s.contains("CREATE TABLE " + t), "缺表 " + t);
        }
        assertTrue(s.contains("ADD COLUMN scope_version INT NOT NULL DEFAULT 0"), "缺 scope_version 列");
        assertTrue(s.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"), "建表约定不符");
        // 13 个角色范围种子
        for (String r : new String[]{"PLATFORM_ADMIN", "SYS_ADMIN", "INST_MGR", "INST_PM", "INST_CLERK",
                "INSTALLER", "MAINT_MGR", "MAINT_QUALITY", "MAINT_BIZ", "MAINT_SUP", "MAINT_TECH",
                "CLIENT_ADMIN", "CLIENT_SIGNER"}) {
            assertTrue(s.contains("'" + r + "'"), "缺角色范围种子 " + r);
        }
    }

    @Test
    void v35HasNoDestructiveStatements() throws Exception {
        String s = sql("V35__rbac_v2_schema.sql").toUpperCase();
        assertFalse(s.contains("TRUNCATE"), "严禁自动 TRUNCATE");
        assertFalse(s.contains("DELETE"), "严禁自动 DELETE");
        assertFalse(s.contains("DROP TABLE"), "schema 迁移不得删表");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=RbacV2MigrationTest`
Expected: FAIL — `V35__rbac_v2_schema.sql` 资源不存在（ClassPathResource 抛 FileNotFoundException）。

- [ ] **Step 3: Write the migration**

`V35__rbac_v2_schema.sql`（逐字采用 spec §2.1 DDL + §2.2 种子）：

```sql
-- M1 权限底座 schema（spec §2.1）：权限核心五表 + sys_employee.scope_version + 角色默认范围种子。
-- 建表约定跟随仓库（VARCHAR(32) 字符串主键 + VARCHAR(16) 枚举 + DATETIME + KEY idx_ + utf8mb4 + COMMENT）。
-- 纯 schema + 静态配置，全环境执行；不含任何业务数据删除（生产清库是人工上线步骤，AGENTS §6）。

CREATE TABLE sys_org (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  type        VARCHAR(16)  NOT NULL COMMENT 'COMPANY/DEPT/TEAM/UNIT',
  name        VARCHAR(128) NOT NULL,
  code        VARCHAR(64)  NULL,
  parent_id   VARCHAR(32)  NULL,
  leader_id   VARCHAR(32)  NULL COMMENT '负责人 empId/use_unit.id',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
  sort_order  INT          NULL,
  created_at  DATETIME     NOT NULL,
  UNIQUE KEY uk_org_code (code),
  KEY idx_org_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织树：公司→部门→班组；使用单位单级（应用层禁挂子节点）';

CREATE TABLE sys_user_role (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  user_id     VARCHAR(32)  NOT NULL,
  user_type   VARCHAR(16)  NOT NULL COMMENT 'EMPLOYEE/UNIT',
  role_code   VARCHAR(32)  NOT NULL COMMENT '规范角色码（新码）',
  org_id      VARCHAR(32)  NULL COMMENT '该角色组织锚点：TEAM→班组节点 / ORG_FLAT→单位节点',
  created_at  DATETIME     NOT NULL,
  UNIQUE KEY uk_user_role (user_id, user_type, role_code),
  KEY idx_ur_user (user_id),
  KEY idx_ur_role (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色多对多；同部门多角色应用层校验、跨部门禁止（R7）';

CREATE TABLE sys_role_scope (
  role_code   VARCHAR(32)  NOT NULL PRIMARY KEY,
  scope       VARCHAR(16)  NOT NULL COMMENT 'SELF/TEAM/PROJECT/ORG/ORG_FLAT/ALL',
  scope_ref   VARCHAR(32)  NULL COMMENT '固定范围引用（多数角色为空，动态解析）',
  version     INT          NOT NULL DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色默认数据范围配置';

CREATE TABLE sys_grant (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  grantee_id  VARCHAR(32)  NOT NULL,
  perm_code   VARCHAR(64)  NOT NULL COMMENT '追加权限点',
  scope       VARCHAR(16)  NULL,
  scope_ref   VARCHAR(32)  NULL,
  start_at    DATETIME     NOT NULL,
  end_at      DATETIME     NOT NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACTIVE/REVOKED/WITHDRAWN',
  granted_by  VARCHAR(32)  NOT NULL,
  reason      VARCHAR(255) NULL,
  created_at  DATETIME     NOT NULL,
  KEY idx_grant_grantee (grantee_id, status),
  KEY idx_grant_end (end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限时授权：end>start 应用层校验，到期自动回收';

CREATE TABLE sys_audit_log (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  actor_id    VARCHAR(32)  NULL,
  actor_role  VARCHAR(32)  NULL,
  action      VARCHAR(64)  NOT NULL COMMENT '权限码动作',
  target      VARCHAR(64)  NULL,
  org_id      VARCHAR(32)  NULL,
  project_id  VARCHAR(32)  NULL,
  before_snap TEXT         NULL,
  after_snap  TEXT         NULL,
  hash_ref    VARCHAR(128) NULL,
  reason      VARCHAR(255) NULL,
  device      VARCHAR(64)  NULL,
  ip          VARCHAR(64)  NULL,
  gps         VARCHAR(64)  NULL,
  result      VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED/BLOCKED',
  created_at  DATETIME     NOT NULL,
  KEY idx_audit_actor (actor_id, created_at),
  KEY idx_audit_action (action, created_at),
  KEY idx_audit_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务审计：只读不可删改；旧 op_log 保留满足留存';

ALTER TABLE sys_employee
  ADD COLUMN scope_version INT NOT NULL DEFAULT 0 COMMENT '角色/范围变更递增→旧 token 失效（M1）';

-- 角色默认数据范围种子（spec §2.2，全环境静态配置）
INSERT INTO sys_role_scope (role_code, scope, scope_ref, version) VALUES
  ('PLATFORM_ADMIN', 'ALL',      NULL, 1),
  ('SYS_ADMIN',      'ALL',      NULL, 1),
  ('INST_MGR',       'ORG',      NULL, 1),
  ('INST_PM',        'PROJECT',  NULL, 1),
  ('INST_CLERK',     'PROJECT',  NULL, 1),
  ('INSTALLER',      'SELF',     NULL, 1),
  ('MAINT_MGR',      'ALL',      NULL, 1),
  ('MAINT_QUALITY',  'ORG',      NULL, 1),
  ('MAINT_BIZ',      'ORG',      NULL, 1),
  ('MAINT_SUP',      'TEAM',     NULL, 1),
  ('MAINT_TECH',     'SELF',     NULL, 1),
  ('CLIENT_ADMIN',   'ORG_FLAT', NULL, 1),
  ('CLIENT_SIGNER',  'ORG_FLAT', NULL, 1);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RbacV2MigrationTest,MigrationIntegrityTest`
Expected: PASS（V35 结构断言全过；MigrationIntegrityTest 无重复版本，V35 唯一）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V35__rbac_v2_schema.sql backend/src/test/java/com/cqwlw/maintenance/RbacV2MigrationTest.java
git commit -m "feat: V35 权限底座五表与角色范围种子"
```

---

### Task 2: V36 回填迁移（dev/test，生产空库 no-op）

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__rbac_v2_backfill.sql`
- Modify: `backend/src/test/java/com/cqwlw/maintenance/RbacV2MigrationTest.java`（增 V36 断言）

**Interfaces:**
- Consumes: Task 1 的五表 + `sys_employee.role` 旧码 + 现有 `group_name`/`use_unit`。
- Produces: `sys_user_role` 由旧角色映射回填；`sys_org` 公司/部门/班组/单位节点。不含任何新建账号（张工归 Task 12 Seeder）。

- [ ] **Step 1: Write the failing test**

在 `RbacV2MigrationTest` 追加：

```java
    @Test
    void v36BackfillsUserRolesAndOrgWithoutNewAccounts() throws Exception {
        String s = sql("V36__rbac_v2_backfill.sql");
        assertTrue(s.contains("INSERT IGNORE INTO sys_user_role"), "缺 sys_user_role 回填");
        assertTrue(s.contains("INSERT IGNORE INTO sys_org"), "缺 sys_org 节点");
        assertTrue(s.contains("MAINT_TECH"), "ASSISTANT/WORKER 应合并映射 MAINT_TECH");
        assertTrue(s.contains("CLIENT_ADMIN"), "use_unit 账号应回填 CLIENT_ADMIN");
        // 旧→新映射方向（spec §6.1 / M0 表1）
        assertTrue(s.contains("INSTALL_MANAGER") && s.contains("INST_PM"), "INSTALL_MANAGER→INST_PM");
        assertTrue(s.contains("LEADER") && s.contains("MAINT_SUP"), "LEADER→MAINT_SUP");
        String up = s.toUpperCase();
        assertFalse(up.contains("TRUNCATE"), "严禁自动 TRUNCATE");
        assertFalse(up.contains("DELETE"), "严禁自动 DELETE");
        // 不得新建账号（张工归 DemoDataSeeder）：回填只从 sys_employee/use_unit 派生
        assertFalse(s.contains("123456"), "迁移不得写入弱口令账号");
        assertFalse(s.contains("INST_MGR"), "V36 不得新建 INST_MGR 账号（张工归 Seeder）");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=RbacV2MigrationTest#v36BackfillsUserRolesAndOrgWithoutNewAccounts`
Expected: FAIL — V36 资源不存在。

- [ ] **Step 3: Write the migration**

`V36__rbac_v2_backfill.sql`（类比 V31 department UPDATE 的数据迁移；空库时 SELECT 无行＝no-op）：

```sql
-- M1 权限底座回填（spec §6.1）：dev/test 由存量旧角色派生 sys_user_role + sys_org 节点。
-- 生产空库时 INSERT...SELECT 命中 0 行（仅部门骨架 VALUES 3 行，无账号/无业务数据）；张工归 DemoDataSeeder。
-- 区域仍留独立 region 表，不进 org 树（spec §4.4）。旧码值不改（spec §4.6 / M1-5）。
-- 主键长度护栏：所有派生 id 用 前缀(3) + SUBSTRING(MD5(...),1,28) = 31 ≤ VARCHAR(32)；
--   sys_org.id 与 sys_user_role.org_id 锚定必须用同一表达式。INSERT IGNORE 撞唯一键即跳过（可重跑）。

-- 1) 组织节点：公司（company）/ 三部门 / 班组（group_name）/ 使用单位单级（use_unit）。
INSERT IGNORE INTO sys_org (id, type, name, code, parent_id, leader_id, status, sort_order, created_at)
SELECT CONCAT('oc_', SUBSTRING(MD5(id), 1, 28)), 'COMPANY', name, CONCAT('CO_', id), NULL, NULL, 'ACTIVE', 0, NOW()
FROM company;

INSERT IGNORE INTO sys_org (id, type, name, code, parent_id, leader_id, status, sort_order, created_at)
VALUES
  ('org_dept_install', 'DEPT', '安装部', 'DEPT_INSTALL', NULL, NULL, 'ACTIVE', 1, NOW()),
  ('org_dept_maint',   'DEPT', '维保部', 'DEPT_MAINT',   NULL, NULL, 'ACTIVE', 2, NOW()),
  ('org_dept_unit',    'DEPT', '使用单位', 'DEPT_UNIT',  NULL, NULL, 'ACTIVE', 3, NOW());

-- 班组节点：每个非空 group_name 一个 TEAM 节点，挂维保部下。
INSERT IGNORE INTO sys_org (id, type, name, code, parent_id, leader_id, status, sort_order, created_at)
SELECT CONCAT('ot_', SUBSTRING(MD5(group_name), 1, 28)), 'TEAM', group_name, CONCAT('TEAM_', MD5(group_name)),
       'org_dept_maint', NULL, 'ACTIVE', 0, NOW()
FROM sys_employee
WHERE group_name IS NOT NULL AND group_name <> ''
GROUP BY group_name;

-- 使用单位单级节点：每个 use_unit 一个 UNIT 节点，挂使用单位部下（应用层禁挂子节点）。
INSERT IGNORE INTO sys_org (id, type, name, code, parent_id, leader_id, status, sort_order, created_at)
SELECT CONCAT('ou_', SUBSTRING(MD5(id), 1, 28)), 'UNIT', unit_name, CONCAT('UNIT_', id), 'org_dept_unit', NULL, 'ACTIVE', 0, NOW()
FROM use_unit;

-- 2) 员工角色回填：旧码→规范码（M0 表1；ASSISTANT/WORKER 合并 MAINT_TECH，D-A6）。
--    org_id 锚定：TEAM 角色（MAINT_SUP/MAINT_TECH）→班组节点（与上方 ot_ 表达式一致）；其余 NULL。
INSERT IGNORE INTO sys_user_role (id, user_id, user_type, role_code, org_id, created_at)
SELECT CONCAT('ur_', SUBSTRING(MD5(CONCAT(e.id, '_', m.new_code)), 1, 28)), e.id, 'EMPLOYEE', m.new_code,
       CASE WHEN m.new_code IN ('MAINT_SUP', 'MAINT_TECH') AND e.group_name IS NOT NULL AND e.group_name <> ''
            THEN CONCAT('ot_', SUBSTRING(MD5(e.group_name), 1, 28)) ELSE NULL END,
       NOW()
FROM sys_employee e
JOIN (
  SELECT 'ADMIN' AS old_code, 'PLATFORM_ADMIN' AS new_code
  UNION ALL SELECT 'SYS_ADMIN', 'SYS_ADMIN'
  UNION ALL SELECT 'INSTALL_MANAGER', 'INST_PM'
  UNION ALL SELECT 'INSTALL_CLERK', 'INST_CLERK'
  UNION ALL SELECT 'INSTALL_WORKER', 'INSTALLER'
  UNION ALL SELECT 'MAINT_MANAGER', 'MAINT_MGR'
  UNION ALL SELECT 'LEADER', 'MAINT_SUP'
  UNION ALL SELECT 'WORKER', 'MAINT_TECH'
  UNION ALL SELECT 'ASSISTANT', 'MAINT_TECH'
  UNION ALL SELECT 'MAINT_BIZ', 'MAINT_BIZ'
  UNION ALL SELECT 'MAINT_QUALITY', 'MAINT_QUALITY'
  UNION ALL SELECT 'UNIT_ADMIN', 'CLIENT_ADMIN'
) m ON e.role = m.old_code;

-- 3) 使用单位门户账号回填（P-3）：use_unit 已开通账号 → CLIENT_ADMIN + CLIENT_SIGNER（user_type=UNIT）。
--    org_id 锚定单位节点（与上方 ou_ 表达式一致）。
INSERT IGNORE INTO sys_user_role (id, user_id, user_type, role_code, org_id, created_at)
SELECT CONCAT('ur_', SUBSTRING(MD5(CONCAT(u.id, '_CLIENT_ADMIN')), 1, 28)), u.id, 'UNIT', 'CLIENT_ADMIN',
       CONCAT('ou_', SUBSTRING(MD5(u.id), 1, 28)), NOW()
FROM use_unit u WHERE u.account IS NOT NULL AND u.account <> '';

INSERT IGNORE INTO sys_user_role (id, user_id, user_type, role_code, org_id, created_at)
SELECT CONCAT('ur_', SUBSTRING(MD5(CONCAT(u.id, '_CLIENT_SIGNER')), 1, 28)), u.id, 'UNIT', 'CLIENT_SIGNER',
       CONCAT('ou_', SUBSTRING(MD5(u.id), 1, 28)), NOW()
FROM use_unit u WHERE u.account IS NOT NULL AND u.account <> '';
```

> 表名/列名已核对源码：`Company`→表 `company`（列 `id`/`name`）；`UseUnit`→表 `use_unit`（列 `id`/`unit_name`/`account`）；`Employee`→表 `sys_employee`（列 `id`/`role`/`group_name`）。所有派生主键 `前缀(3)+SUBSTRING(MD5,1,28)=31≤32`；`code` 列 VARCHAR(64) 容 `TEAM_`+32 位 MD5=37。部门骨架 3 行为静态参考数据（非账号/非业务数据），生产空库仅此一插入，其余 INSERT...SELECT 命中 0 行。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RbacV2MigrationTest,MigrationIntegrityTest`
Expected: PASS（V36 结构断言全过；V35/V36 版本唯一）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V36__rbac_v2_backfill.sql backend/src/test/java/com/cqwlw/maintenance/RbacV2MigrationTest.java
git commit -m "feat: V36 权限底座回填（dev/test，生产空库 no-op）"
```

---

### Task 3: 五表实体 + Mapper + Employee.scopeVersion

**Files:**
- Create: `entity/SysOrg.java` `entity/SysUserRole.java` `entity/SysRoleScope.java` `entity/SysGrant.java` `entity/SysAuditLog.java`
- Create: `mapper/SysOrgMapper.java` `mapper/SysUserRoleMapper.java` `mapper/SysRoleScopeMapper.java` `mapper/SysGrantMapper.java` `mapper/SysAuditLogMapper.java`
- Modify: `entity/Employee.java`（增 scopeVersion）、`mapper/EmployeeMapper.java`（增 bumpScopeVersion）
- Test: `backend/src/test/java/com/cqwlw/maintenance/entity/RbacEntityMappingTest.java`

（所有路径前缀 `backend/src/main/java/com/cqwlw/maintenance/`）

**Interfaces:**
- Consumes: Task 1/2 的表结构；`Ids.next(prefix)`、`TimeUtil.now()`。
- Produces: 实体 public 字段（见下）+ `BaseMapper` CRUD + `EmployeeMapper.bumpScopeVersion(String id)→int`。Task 8/10/11/12 依赖这些。

- [ ] **Step 1: Write the failing test**

`RbacEntityMappingTest.java`：

```java
package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 五表实体 @TableName 映射 + Employee.scopeVersion + bumpScopeVersion 签名（无库，反射断言）。 */
class RbacEntityMappingTest {

    private String table(Class<?> c) {
        TableName a = c.getAnnotation(TableName.class);
        assertNotNull(a, c.getSimpleName() + " 缺 @TableName");
        return a.value();
    }

    @Test
    void entitiesMapToRbacTables() {
        assertEquals("sys_org", table(SysOrg.class));
        assertEquals("sys_user_role", table(SysUserRole.class));
        assertEquals("sys_role_scope", table(SysRoleScope.class));
        assertEquals("sys_grant", table(SysGrant.class));
        assertEquals("sys_audit_log", table(SysAuditLog.class));
    }

    @Test
    void employeeHasScopeVersion() throws Exception {
        Field f = Employee.class.getField("scopeVersion");
        assertEquals(Integer.class, f.getType());
    }

    @Test
    void employeeMapperHasBumpScopeVersion() throws Exception {
        assertNotNull(EmployeeMapper.class.getMethod("bumpScopeVersion", String.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=RbacEntityMappingTest`
Expected: FAIL — 编译错误（SysOrg 等类不存在 / Employee.scopeVersion 不存在 / bumpScopeVersion 不存在）。

- [ ] **Step 3: Write the entities, mappers, and Employee changes**

`entity/SysOrg.java`：

```java
package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 组织树节点（sys_org）：公司→部门→班组；使用单位单级。 */
@Data
@TableName("sys_org")
public class SysOrg {
    @TableId
    public String id;
    public String type;
    public String name;
    public String code;
    public String parentId;
    public String leaderId;
    public String status;
    public Integer sortOrder;
    public LocalDateTime createdAt;
}
```

`entity/SysUserRole.java`：

```java
package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户-角色多对多（sys_user_role）：同部门兼任允许、跨部门禁止（R7）。 */
@Data
@TableName("sys_user_role")
public class SysUserRole {
    @TableId
    public String id;
    public String userId;
    public String userType;
    public String roleCode;
    public String orgId;
    public LocalDateTime createdAt;
}
```

`entity/SysRoleScope.java`：

```java
package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 角色默认数据范围（sys_role_scope）：6 范围枚举 + version。 */
@Data
@TableName("sys_role_scope")
public class SysRoleScope {
    @TableId
    public String roleCode;
    public String scope;
    public String scopeRef;
    public Integer version;
}
```

`entity/SysGrant.java`：

```java
package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 限时授权（sys_grant）：PENDING/ACTIVE/REVOKED/WITHDRAWN，到期自动回收。 */
@Data
@TableName("sys_grant")
public class SysGrant {
    @TableId
    public String id;
    public String granteeId;
    public String permCode;
    public String scope;
    public String scopeRef;
    public LocalDateTime startAt;
    public LocalDateTime endAt;
    public String status;
    public String grantedBy;
    public String reason;
    public LocalDateTime createdAt;
}
```

`entity/SysAuditLog.java`：

```java
package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 业务审计（sys_audit_log）：只读不可删改；result∈SUCCESS/FAILED/BLOCKED。 */
@Data
@TableName("sys_audit_log")
public class SysAuditLog {
    @TableId
    public String id;
    public String actorId;
    public String actorRole;
    public String action;
    public String target;
    public String orgId;
    public String projectId;
    public String beforeSnap;
    public String afterSnap;
    public String hashRef;
    public String reason;
    public String device;
    public String ip;
    public String gps;
    public String result;
    public LocalDateTime createdAt;
}
```

五个 Mapper（同模式，逐个创建）：

```java
package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.SysOrg;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysOrgMapper extends BaseMapper<SysOrg> {
}
```

```java
package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
```

```java
package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.SysRoleScope;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysRoleScopeMapper extends BaseMapper<SysRoleScope> {
}
```

```java
package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.SysGrant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysGrantMapper extends BaseMapper<SysGrant> {
}
```

```java
package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.SysAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {
}
```

`entity/Employee.java`：在 `public String jobTitle;` 之后增一行（保持 public 字段风格）：

```java
    public Integer scopeVersion;
```

`mapper/EmployeeMapper.java`：在 `unbindOpenidExcept` 之后增（显式 SQL 自增，避免 MyBatis-Plus null 跳过陷阱）：

```java
    /** 角色/范围变更或限时授权回收时递增 scope_version，使旧 token 立即失效（spec §4.2/§5.1）。 */
    @Update("UPDATE sys_employee SET scope_version = scope_version + 1 WHERE id = #{id}")
    int bumpScopeVersion(@Param("id") String id);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RbacEntityMappingTest`
Expected: PASS（3 断言全过）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/entity/Sys*.java backend/src/main/java/com/cqwlw/maintenance/mapper/Sys*.java backend/src/main/java/com/cqwlw/maintenance/entity/Employee.java backend/src/main/java/com/cqwlw/maintenance/mapper/EmployeeMapper.java backend/src/test/java/com/cqwlw/maintenance/entity/RbacEntityMappingTest.java
git commit -m "feat: 权限底座五表实体与 mapper，Employee 增 scopeVersion"
```

---

### Task 4: RoleCatalog v2（13 规范角色 + 旧码别名）

**Files:**
- Modify: `auth/RoleCatalog.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/auth/RoleCatalogV2Test.java`

**Interfaces:**
- Consumes: 现有 `RoleCatalog.Role(code,department,name,adminConsole,parentRole)` record、12 旧码常量、`codes()`/`role()`/`departmentOf()`/`nameOf()`/`canUseAdminConsole()`/`known()`/`assignableRoles()`。
- Produces:
  - `static String canonical(String anyCode)` — 旧码/新码 → 规范码（未知返回 null）。
  - `static Set<String> canonicalCodes()` — 13 规范码集合。
  - `static Role canonicalRole(String anyCode)` — 规范角色 record（未知 null）。
  - `static boolean sameDepartmentAssignable(String roleA, String roleB)` — 同部门 true、跨部门 false（R7）。
  - 现有 `codes()` 仍返回 **12 旧码**；`role(旧码)` 仍返回旧 record（RbacMatrixTest 锁定）；`canUseAdminConsole(UNIT_ADMIN)` 仍 **false**。

- [ ] **Step 1: Write the failing test**

`RoleCatalogV2Test.java`：

```java
package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RoleCatalog v2：旧→新别名、13 规范角色、部门/adminConsole、同部门兼任（R7）。 */
class RoleCatalogV2Test {

    @Test
    void legacyCodesAliasToCanonical() {
        assertEquals("PLATFORM_ADMIN", RoleCatalog.canonical("ADMIN"));
        assertEquals("SYS_ADMIN", RoleCatalog.canonical("SYS_ADMIN"));
        assertEquals("INST_PM", RoleCatalog.canonical("INSTALL_MANAGER"));
        assertEquals("INST_CLERK", RoleCatalog.canonical("INSTALL_CLERK"));
        assertEquals("INSTALLER", RoleCatalog.canonical("INSTALL_WORKER"));
        assertEquals("MAINT_MGR", RoleCatalog.canonical("MAINT_MANAGER"));
        assertEquals("MAINT_SUP", RoleCatalog.canonical("LEADER"));
        assertEquals("MAINT_TECH", RoleCatalog.canonical("WORKER"));
        assertEquals("MAINT_TECH", RoleCatalog.canonical("ASSISTANT"));
        assertEquals("CLIENT_ADMIN", RoleCatalog.canonical("UNIT_ADMIN"));
        // 新码自映射
        assertEquals("INST_MGR", RoleCatalog.canonical("INST_MGR"));
        assertEquals("CLIENT_SIGNER", RoleCatalog.canonical("CLIENT_SIGNER"));
        assertNull(RoleCatalog.canonical("HACKER"));
        assertNull(RoleCatalog.canonical(null));
    }

    @Test
    void canonicalCodesAreThirteen() {
        assertEquals(Set.of("PLATFORM_ADMIN", "SYS_ADMIN", "INST_MGR", "INST_PM", "INST_CLERK", "INSTALLER",
                "MAINT_MGR", "MAINT_SUP", "MAINT_TECH", "MAINT_BIZ", "MAINT_QUALITY",
                "CLIENT_ADMIN", "CLIENT_SIGNER"), RoleCatalog.canonicalCodes());
    }

    @Test
    void canonicalDepartmentsAndConsole() {
        assertEquals("INSTALL", RoleCatalog.canonicalRole("INST_MGR").department());
        assertEquals("MAINT", RoleCatalog.canonicalRole("MAINT_SUP").department());
        assertEquals("UNIT", RoleCatalog.canonicalRole("CLIENT_SIGNER").department());
        assertEquals("PLATFORM", RoleCatalog.canonicalRole("PLATFORM_ADMIN").department());
        assertTrue(RoleCatalog.canonicalRole("INST_MGR").adminConsole());
        assertTrue(RoleCatalog.canonicalRole("MAINT_MGR").adminConsole());
        // 小程序-only 3 角色
        assertFalse(RoleCatalog.canonicalRole("MAINT_TECH").adminConsole());
        assertFalse(RoleCatalog.canonicalRole("INSTALLER").adminConsole());
        assertFalse(RoleCatalog.canonicalRole("CLIENT_SIGNER").adminConsole());
        // M1：CLIENT_ADMIN 仍不可登录管理端（M5 新能力）
        assertFalse(RoleCatalog.canonicalRole("CLIENT_ADMIN").adminConsole());
        assertFalse(RoleCatalog.canUseAdminConsole("UNIT_ADMIN"));
    }

    @Test
    void sameDepartmentAssignableCrossDepartmentRejected() {
        assertTrue(RoleCatalog.sameDepartmentAssignable("MAINT_SUP", "MAINT_TECH"));
        assertTrue(RoleCatalog.sameDepartmentAssignable("INST_PM", "INST_CLERK"));
        assertFalse(RoleCatalog.sameDepartmentAssignable("MAINT_SUP", "INST_PM"));
        assertFalse(RoleCatalog.sameDepartmentAssignable("CLIENT_ADMIN", "MAINT_TECH"));
        assertFalse(RoleCatalog.sameDepartmentAssignable("HACKER", "MAINT_TECH"));
    }

    @Test
    void legacyCodesUnchangedForCompat() {
        // RbacMatrixTest.roleCatalogMatchesDesignDoc 锁定：codes() 仍 12 旧码
        assertEquals(12, RoleCatalog.codes().size());
        assertTrue(RoleCatalog.codes().contains("ADMIN"));
        assertTrue(RoleCatalog.codes().contains("UNIT_ADMIN"));
        assertEquals("维保班组长", RoleCatalog.nameOf("LEADER"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=RoleCatalogV2Test`
Expected: FAIL — 编译错误（canonical/canonicalCodes/canonicalRole/sameDepartmentAssignable 不存在）。

- [ ] **Step 3: Implement RoleCatalog v2 additions**

在 `RoleCatalog.java` 现有 12 旧码 ROLES 之外，**追加**规范角色表与别名表（不改动现有 `ROLES`/`codes()`/`role()`/`nameOf()`/`canUseAdminConsole()` 的旧码行为）。在类内追加：

```java
    /** 规范角色（13）：code→Role。旧码经 ALIAS 翻译到此表（spec §3.1）。 */
    private static final java.util.Map<String, Role> CANONICAL = new java.util.LinkedHashMap<>();
    /** 旧码→规范码别名（spec §3.1 表）。 */
    private static final java.util.Map<String, String> ALIAS = new java.util.LinkedHashMap<>();

    static {
        // 规范角色：adminConsole 按 spec §3.1（CLIENT_ADMIN M1 仍 false，M5 才开 Web）
        CANONICAL.put("PLATFORM_ADMIN", new Role("PLATFORM_ADMIN", "PLATFORM", "平台管理员", true, null));
        CANONICAL.put("SYS_ADMIN", new Role("SYS_ADMIN", "PLATFORM", "系统管理员", true, null));
        CANONICAL.put("INST_MGR", new Role("INST_MGR", "INSTALL", "安装部主管", true, null));
        CANONICAL.put("INST_PM", new Role("INST_PM", "INSTALL", "安装部项目负责人", true, "INST_MGR"));
        CANONICAL.put("INST_CLERK", new Role("INST_CLERK", "INSTALL", "安装部资料员", true, "INST_MGR"));
        CANONICAL.put("INSTALLER", new Role("INSTALLER", "INSTALL", "安装员", false, "INST_MGR"));
        CANONICAL.put("MAINT_MGR", new Role("MAINT_MGR", "MAINT", "维保部经理", true, null));
        CANONICAL.put("MAINT_SUP", new Role("MAINT_SUP", "MAINT", "维保主管", true, "MAINT_MGR"));
        CANONICAL.put("MAINT_TECH", new Role("MAINT_TECH", "MAINT", "维保人员", false, "MAINT_SUP"));
        CANONICAL.put("MAINT_BIZ", new Role("MAINT_BIZ", "MAINT", "维保商务", true, "MAINT_MGR"));
        CANONICAL.put("MAINT_QUALITY", new Role("MAINT_QUALITY", "MAINT", "维保质量", true, "MAINT_MGR"));
        CANONICAL.put("CLIENT_ADMIN", new Role("CLIENT_ADMIN", "UNIT", "使用单位管理员", false, null));
        CANONICAL.put("CLIENT_SIGNER", new Role("CLIENT_SIGNER", "UNIT", "使用单位签字人", false, null));

        ALIAS.put("ADMIN", "PLATFORM_ADMIN");
        ALIAS.put("SYS_ADMIN", "SYS_ADMIN");
        ALIAS.put("INSTALL_MANAGER", "INST_PM");
        ALIAS.put("INSTALL_CLERK", "INST_CLERK");
        ALIAS.put("INSTALL_WORKER", "INSTALLER");
        ALIAS.put("MAINT_MANAGER", "MAINT_MGR");
        ALIAS.put("LEADER", "MAINT_SUP");
        ALIAS.put("WORKER", "MAINT_TECH");
        ALIAS.put("ASSISTANT", "MAINT_TECH");
        ALIAS.put("MAINT_BIZ", "MAINT_BIZ");
        ALIAS.put("MAINT_QUALITY", "MAINT_QUALITY");
        ALIAS.put("UNIT_ADMIN", "CLIENT_ADMIN");
    }

    /** 旧码/新码 → 规范码；未知返回 null。 */
    public static String canonical(String anyCode) {
        if (anyCode == null) {
            return null;
        }
        if (CANONICAL.containsKey(anyCode)) {
            return anyCode;
        }
        return ALIAS.get(anyCode);
    }

    /** 13 规范码集合。 */
    public static java.util.Set<String> canonicalCodes() {
        return java.util.Collections.unmodifiableSet(CANONICAL.keySet());
    }

    /** 规范角色 record；未知返回 null。 */
    public static Role canonicalRole(String anyCode) {
        String c = canonical(anyCode);
        return c == null ? null : CANONICAL.get(c);
    }

    /** 同部门方可兼任（docs/14 R7）；跨部门/未知拒绝。 */
    public static boolean sameDepartmentAssignable(String roleA, String roleB) {
        Role a = canonicalRole(roleA);
        Role b = canonicalRole(roleB);
        return a != null && b != null && a.department().equals(b.department());
    }
```

> 现有 `Role` record 的 `parentRole` 字段类型须与上述 `null`/字符串兼容（现状为 String）。若现有 ROLES 静态块与新增 CANONICAL 静态块顺序冲突，把新增内容放入**同一个** static 块末尾或独立 static 块（Java 允许多 static 块，按源码顺序执行）。`codes()`/`role()`/`nameOf()`/`canUseAdminConsole()` 保持原样不动。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=RoleCatalogV2Test,RbacMatrixTest`
Expected: PASS（v2 新断言全过；RbacMatrixTest 字面不动仍绿，codes()==12、nameOf(LEADER)=="维保班组长"）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/auth/RoleCatalog.java backend/src/test/java/com/cqwlw/maintenance/auth/RoleCatalogV2Test.java
git commit -m "feat: RoleCatalog v2 十三规范角色与旧码别名"
```

---

### Task 5: PermissionMatrix v2（86 码三态，保留旧别名）

**Files:**
- Modify: `auth/PermissionMatrix.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/auth/PermissionMatrixV2Test.java`

**Interfaces:**
- Consumes: `RoleCatalog.canonicalCodes()`（Task 4）；现有 24 旧常量 + `GRANTS` + `allows`/`capabilities`/`requireKnownCapability`/`grant`。
- Produces:
  - `enum TriState { Y, C, DENY }`（DENY 即 spec 的 `-`）。
  - `static TriState state(String role, String code)` — 规范角色×新码三态；未知角色→DENY，未知码→IllegalArgumentException。
  - `static boolean allowsNew(String role, String code)` — `state==Y`。
  - `static Set<String> canonicalCapabilities()` — 86 新码集合。
  - 新码字符串常量（如 `public static final String HANDOVER_VIEW = "handover:view";` 等，86 个）。
  - 现有 `allows(role,旧码)`/`capabilities()`（24）/24 旧常量 **保持不变**（Task 6 再把 allows 委托翻译器）。

- [ ] **Step 1: Write the failing test**

`PermissionMatrixV2Test.java`：

```java
package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 矩阵 v2：三态 state、allowsNew 要求 Y、86 码、fail-closed、24 旧常量仍在。 */
class PermissionMatrixV2Test {

    @Test
    void canonicalCapabilitiesAreEightySix() {
        assertEquals(86, PermissionMatrix.canonicalCapabilities().size());
        assertTrue(PermissionMatrix.canonicalCapabilities().contains("handover:view"));
        assertTrue(PermissionMatrix.canonicalCapabilities().contains("record:spotcheck"));
        assertTrue(PermissionMatrix.canonicalCapabilities().contains("user:reset_password"));
    }

    @Test
    void triStateSamplesFromRolesDoc() {
        // roles-permissions.md §4.1：安装主管✔ handover:review，资料员无
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("INST_MGR", "handover:review"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("INST_CLERK", "handover:review"));
        // §4.1 handover:view：资料员△=C
        assertEquals(PermissionMatrix.TriState.C, PermissionMatrix.state("INST_CLERK", "handover:view"));
        // §4.3 record:view：CA 目标态无（M0 row7），旧别名 record:read 仍含 UA（Task 6 冻结）
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("CLIENT_ADMIN", "record:view"));
        // M0 §2.2 扩展：MQ record:spotcheck✔、MB client:edit✔、IW handover:inspect✔
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("MAINT_QUALITY", "record:spotcheck"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("MAINT_BIZ", "client:edit"));
        assertEquals(PermissionMatrix.TriState.Y, PermissionMatrix.state("INSTALLER", "handover:inspect"));
        // SYS_ADMIN 镜像 PLATFORM_ADMIN
        assertEquals(PermissionMatrix.state("PLATFORM_ADMIN", "config:manage"),
                PermissionMatrix.state("SYS_ADMIN", "config:manage"));
    }

    @Test
    void allowsNewRequiresY() {
        assertTrue(PermissionMatrix.allowsNew("INST_MGR", "handover:review"));
        assertFalse(PermissionMatrix.allowsNew("INST_CLERK", "handover:view")); // C 不是 Y
        assertFalse(PermissionMatrix.allowsNew("MAINT_TECH", "config:manage"));
    }

    @Test
    void unknownRoleDeniedUnknownCodeFailsLoudly() {
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state("HACKER", "handover:view"));
        assertEquals(PermissionMatrix.TriState.DENY, PermissionMatrix.state(null, "handover:view"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.state("INST_MGR", "no:such_code"));
        assertThrows(IllegalArgumentException.class, () -> PermissionMatrix.allowsNew("INST_MGR", null));
    }

    @Test
    void legacyConstantsAndCapabilitiesPreserved() {
        // RbacMatrixTest 锁定：24 旧码仍在
        assertEquals(24, PermissionMatrix.capabilities().size());
        assertEquals("region:write", PermissionMatrix.REGION_WRITE);
        assertEquals("console:read", PermissionMatrix.CONSOLE_READ);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=PermissionMatrixV2Test`
Expected: FAIL — 编译错误（TriState/state/allowsNew/canonicalCapabilities 不存在）。

- [ ] **Step 3: Implement the tri-state matrix**

在 `PermissionMatrix.java` 追加（**不改动**现有 24 常量/`CAPABILITIES`/`GRANTS`/`allows`/`capabilities`/`requireKnownCapability`/`grant`）。新增嵌套枚举、86 新码常量、`STATES` 表与 API。`STATES` 用 `Map<String code, Map<String canonicalRole, TriState>>`，缺省 DENY。

```java
    /** 三态：Y 可操作 / C 只读或需审批 / DENY 禁止（spec 的 '-'）。 */
    public enum TriState { Y, C, DENY }

    // ==== 86 新码常量（71 base + 15 extension）====
    public static final String HANDOVER_VIEW = "handover:view";
    public static final String HANDOVER_CREATE = "handover:create";
    public static final String HANDOVER_EDIT = "handover:edit";
    public static final String HANDOVER_DOC_UPLOAD = "handover:doc_upload";
    public static final String HANDOVER_DOC_DELETE = "handover:doc_delete";
    public static final String HANDOVER_SUBMIT = "handover:submit";
    public static final String HANDOVER_REVIEW = "handover:review";
    public static final String HANDOVER_REVIEW_RETURN = "handover:review_return";
    public static final String HANDOVER_RECEIVE = "handover:receive";
    public static final String HANDOVER_INSPECT = "handover:inspect";
    public static final String HANDOVER_RECTIFY = "handover:rectify";
    public static final String HANDOVER_SIGN_INSTALL = "handover:sign_install";
    public static final String HANDOVER_SIGN_MAINT = "handover:sign_maint";
    public static final String HANDOVER_VOID = "handover:void";
    public static final String HANDOVER_EXPORT = "handover:export";
    public static final String HANDOVER_NOTIFY_CLIENT = "handover:notify_client";
    public static final String PROJECT_VIEW = "project:view";
    public static final String PROJECT_EDIT = "project:edit";
    public static final String CONTRACT_VIEW = "contract:view";
    public static final String CONTRACT_EDIT = "contract:edit";
    public static final String ELEVATOR_VIEW = "elevator:view";
    public static final String ELEVATOR_EDIT = "elevator:edit";
    public static final String ELEVATOR_QR = "elevator:qr";
    public static final String ELEVATOR_TRANSFER = "elevator:transfer";
    public static final String ELEVATOR_EXPORT = "elevator:export";
    public static final String PLAN_VIEW = "plan:view";
    public static final String PLAN_GENERATE = "plan:generate";
    public static final String TASK_ACCEPT = "task:accept";
    public static final String TASK_ASSIGN_PARTNER = "task:assign_partner";
    public static final String CHECKLIST_FILL = "checklist:fill";
    public static final String RECORD_VIEW = "record:view";
    public static final String RECORD_SUBMIT = "record:submit";
    public static final String RECORD_SIGN_TECH = "record:sign_tech";
    public static final String RECORD_REVIEW = "record:review";
    public static final String RECORD_SIGN_REQUEST = "record:sign_request";
    public static final String RECORD_SIGN = "record:sign";
    public static final String RECORD_REFUSE = "record:refuse";
    public static final String RECORD_ARCHIVE = "record:archive";
    public static final String RECORD_AMEND = "record:amend";
    public static final String RECORD_EXPORT = "record:export";
    public static final String ORDER_CREATE = "order:create";
    public static final String ORDER_CREATE_PROXY = "order:create_proxy";
    public static final String ORDER_VIEW_POOL = "order:view_pool";
    public static final String ORDER_VIEW_OWN = "order:view_own";
    public static final String ORDER_GRAB = "order:grab";
    public static final String ORDER_DISPATCH = "order:dispatch";
    public static final String ORDER_ARRIVE = "order:arrive";
    public static final String ORDER_PROCESS = "order:process";
    public static final String ORDER_SUBMIT = "order:submit";
    public static final String ORDER_CONFIRM = "order:confirm";
    public static final String ORDER_CLOSE = "order:close";
    public static final String ORDER_CANCEL = "order:cancel";
    public static final String ORDER_ESCALATE = "order:escalate";
    public static final String ORDER_EXPORT = "order:export";
    public static final String CLIENT_INVITE = "client:invite";
    public static final String CLIENT_USER_MANAGE = "client:user_manage";
    public static final String CLIENT_ELEVATOR_VIEW = "client:elevator_view";
    public static final String CLIENT_REPORT = "client:report";
    public static final String CLIENT_SIGN = "client:sign";
    public static final String CLIENT_EVALUATE = "client:evaluate";
    public static final String CLIENT_DOWNLOAD = "client:download";
    public static final String USER_VIEW = "user:view";
    public static final String USER_CREATE = "user:create";
    public static final String USER_DISABLE = "user:disable";
    public static final String ROLE_ASSIGN = "role:assign";
    public static final String INVITE_MANAGE = "invite:manage";
    public static final String STATS_VIEW = "stats:view";
    public static final String STATS_EXPORT = "stats:export";
    public static final String AUDIT_VIEW = "audit:view";
    public static final String CONFIG_MANAGE = "config:manage";
    public static final String DATA_EXPORT_SENSITIVE = "data:export_sensitive";
    // 15 扩展码（M0 §2.2）
    public static final String REPORT_VIEW = "report:view";
    public static final String REPORT_RETRY = "report:retry";
    public static final String FILING_VIEW = "filing:view";
    public static final String FILING_WRITE = "filing:write";
    public static final String REGION_VIEW = "region:view";
    public static final String REGION_WRITE_NEW = "region:write";
    public static final String TEMPLATE_VIEW = "template:view";
    public static final String TEMPLATE_WRITE_NEW = "template:write";
    public static final String TEMPLATE_TOGGLE = "template:toggle";
    public static final String PLATFORM_WORKER_VIEW = "platform:worker_view";
    public static final String PLATFORM_WORKER_REGISTER = "platform:worker_register";
    public static final String PLATFORM_SYNC = "platform:sync";
    public static final String RECORD_SPOTCHECK = "record:spotcheck";
    public static final String CLIENT_EDIT = "client:edit";
    public static final String USER_RESET_PASSWORD = "user:reset_password";
```

> 注意：旧常量 `REGION_WRITE`/`TEMPLATE_WRITE` 已存在（值 "region:write"/"template:write"）。新码常量用 `REGION_WRITE_NEW`/`TEMPLATE_WRITE_NEW` 避免重名，但**字符串值相同**（新旧码同名是允许的，旧常量供旧别名，新常量供新矩阵）。`canonicalCapabilities()` 用字符串集合，不受常量名影响。

接着追加 STATES 构建与 API（数据逐字来自 roles-permissions.md §4 + M0 §2.2 + 扩展角色补码）：

```java
    private static final java.util.Map<String, java.util.Map<String, TriState>> STATES = new java.util.HashMap<>();
    private static final java.util.Set<String> CANONICAL_CAPS = new java.util.LinkedHashSet<>();

    private static void g(String code, TriState st, String... roles) {
        CANONICAL_CAPS.add(code);
        java.util.Map<String, TriState> row = STATES.computeIfAbsent(code, k -> new java.util.HashMap<>());
        for (String r : roles) {
            row.put(r, st);
        }
    }
    private static void y(String code, String... roles) { g(code, TriState.Y, roles); }
    private static void c(String code, String... roles) { g(code, TriState.C, roles); }

    static {
        String PA = "PLATFORM_ADMIN", SA = "SYS_ADMIN", IM = "INST_MGR", IP = "INST_PM", IC = "INST_CLERK",
                IW = "INSTALLER", MM = "MAINT_MGR", MS = "MAINT_SUP", MT = "MAINT_TECH",
                MB = "MAINT_BIZ", MQ = "MAINT_QUALITY", CA = "CLIENT_ADMIN", CS = "CLIENT_SIGNER";
        // §4.1 安装移交（16）
        y(HANDOVER_VIEW, IM, MS, MM, PA, SA); c(HANDOVER_VIEW, IC, IP, CA);
        y(HANDOVER_CREATE, IP);
        y(HANDOVER_EDIT, IP); c(HANDOVER_EDIT, IC);
        y(HANDOVER_DOC_UPLOAD, IC, IP);
        y(HANDOVER_DOC_DELETE, IC, IP);
        y(HANDOVER_SUBMIT, IP);
        y(HANDOVER_REVIEW, IM);
        y(HANDOVER_REVIEW_RETURN, IM);
        y(HANDOVER_RECEIVE, MS);
        y(HANDOVER_INSPECT, IW); c(HANDOVER_INSPECT, MT);
        y(HANDOVER_RECTIFY, IP, IC, IW);
        y(HANDOVER_SIGN_INSTALL, IM, IW);
        y(HANDOVER_SIGN_MAINT, MS, MM);
        y(HANDOVER_VOID, IM, MM, PA, SA);
        y(HANDOVER_EXPORT, IM, MS, MM, PA, SA); c(HANDOVER_EXPORT, CA);
        y(HANDOVER_NOTIFY_CLIENT, MS);
        // §4.2 项目合同电梯（9）
        y(PROJECT_VIEW, MS, MM, PA, SA); c(PROJECT_VIEW, IC, IP, IM, CA);
        y(PROJECT_EDIT, MM, PA, SA);
        y(CONTRACT_VIEW, MM, PA, SA, MB); c(CONTRACT_VIEW, MS, CA);
        y(CONTRACT_EDIT, MM, PA, SA, MB);
        c(ELEVATOR_VIEW, PA, SA, IM, IP, IC, IW, MM, MS, MT, MB, MQ, CA, CS);
        y(ELEVATOR_EDIT, MS, PA, SA);
        y(ELEVATOR_QR, MT, MS, CA);
        y(ELEVATOR_TRANSFER, MM, PA, SA);
        y(ELEVATOR_EXPORT, MM, PA, SA); c(ELEVATOR_EXPORT, MS, CA);
        // §4.3 维保作业（15）
        y(PLAN_VIEW, MS, MM); c(PLAN_VIEW, MT, CA);
        y(PLAN_GENERATE, MS, MM);
        y(TASK_ACCEPT, MT);
        y(TASK_ASSIGN_PARTNER, MS, MT);
        y(CHECKLIST_FILL, MT);
        y(RECORD_VIEW, MS, MM, PA, SA); c(RECORD_VIEW, MT, CS);
        y(RECORD_SUBMIT, MT);
        y(RECORD_SIGN_TECH, MT);
        y(RECORD_REVIEW, MS, MM);
        y(RECORD_SIGN_REQUEST, MT, MS);
        y(RECORD_SIGN, CS);
        y(RECORD_REFUSE, CS);
        y(RECORD_ARCHIVE, MS);
        y(RECORD_AMEND, MM, PA, SA);
        y(RECORD_EXPORT, MS, MM, PA, SA); c(RECORD_EXPORT, CA);
        // §4.4 急修报修（14）
        y(ORDER_CREATE, CA, CS);
        y(ORDER_CREATE_PROXY, MS);
        y(ORDER_VIEW_POOL, MS, MM); c(ORDER_VIEW_POOL, MT);
        y(ORDER_VIEW_OWN, CA, CS);
        y(ORDER_GRAB, MT);
        y(ORDER_DISPATCH, MS, MM);
        c(ORDER_ARRIVE, MT);
        c(ORDER_PROCESS, MT);
        y(ORDER_SUBMIT, MT);
        y(ORDER_CONFIRM, CS);
        y(ORDER_CLOSE, MS);
        y(ORDER_CANCEL, CA, MS);
        y(ORDER_ESCALATE, MM);
        y(ORDER_EXPORT, MS, MM, PA, SA); c(ORDER_EXPORT, CA);
        // §4.5 使用单位（7）
        y(CLIENT_INVITE, CA, MS);
        y(CLIENT_USER_MANAGE, CA);
        y(CLIENT_ELEVATOR_VIEW, CA, CS);
        y(CLIENT_REPORT, CA, CS);
        y(CLIENT_SIGN, CS);
        y(CLIENT_EVALUATE, CA, CS);
        y(CLIENT_DOWNLOAD, CA, CS);
        // §4.6 组织权限审计（10）
        y(USER_VIEW, PA, SA); c(USER_VIEW, IM, MS, MM, CA);
        y(USER_CREATE, IM, MS, MM, CA, PA, SA);
        y(USER_DISABLE, IM, MS, MM, CA, PA, SA);
        y(ROLE_ASSIGN, PA, SA); c(ROLE_ASSIGN, CA);
        y(INVITE_MANAGE, IM, MS, MM, CA, PA, SA);
        y(STATS_VIEW, IM, MS, MM, CA, PA, SA);
        y(STATS_EXPORT, MM, PA, SA); c(STATS_EXPORT, IM, MS, CA);
        y(AUDIT_VIEW, PA, SA); c(AUDIT_VIEW, CA);
        y(CONFIG_MANAGE, PA, SA);
        y(DATA_EXPORT_SENSITIVE, PA, SA); // 审批工作流 M5；M1 只编码平台持有该能力
        // M0 §2.2 扩展（15）
        y(REPORT_VIEW, PA, SA, MM); c(REPORT_VIEW, MS, MQ);
        y(REPORT_RETRY, PA, SA, MM);
        y(FILING_VIEW, PA, SA, IP, IC, MM); c(FILING_VIEW, MQ, IM);
        y(FILING_WRITE, PA, SA, IP, IC, MM);
        y(REGION_VIEW, PA, SA);
        y(REGION_WRITE_NEW, PA, SA);
        y(TEMPLATE_VIEW, PA, SA, MM); c(TEMPLATE_VIEW, MQ);
        y(TEMPLATE_WRITE_NEW, PA, SA, MM, MQ);
        y(TEMPLATE_TOGGLE, PA, SA, MQ);
        y(PLATFORM_WORKER_VIEW, PA, SA); c(PLATFORM_WORKER_VIEW, IP, IC, MM, MS);
        y(PLATFORM_WORKER_REGISTER, PA, SA, MM);
        y(PLATFORM_SYNC, PA, SA);
        y(RECORD_SPOTCHECK, MQ);
        y(CLIENT_EDIT, PA, MB);
        y(USER_RESET_PASSWORD, PA, SA, MM, IM);

        // fail-closed 一致性校验：码集==常量集（86），每码至少一个持有者
        if (CANONICAL_CAPS.size() != 86) {
            throw new IllegalStateException("三态矩阵码数异常: " + CANONICAL_CAPS.size());
        }
        for (String code : CANONICAL_CAPS) {
            java.util.Map<String, TriState> row = STATES.get(code);
            boolean hasHolder = row.values().stream().anyMatch(s -> s == TriState.Y || s == TriState.C);
            if (!hasHolder) {
                throw new IllegalStateException("三态码无持有者: " + code);
            }
        }
    }

    /** 规范角色×新码三态；未知角色→DENY，未知码→IllegalArgumentException（fail-closed）。 */
    public static TriState state(String role, String code) {
        if (code == null || !CANONICAL_CAPS.contains(code)) {
            throw new IllegalArgumentException("未知权限码: " + code);
        }
        String canonical = RoleCatalog.canonical(role);
        if (canonical == null) {
            return TriState.DENY;
        }
        return STATES.get(code).getOrDefault(canonical, TriState.DENY);
    }

    /** 新细粒度入口：要求 Y（C 不算放行；@RequirePerm(新码) M3+ 才标注）。 */
    public static boolean allowsNew(String role, String code) {
        return state(role, code) == TriState.Y;
    }

    /** 86 新码集合。 */
    public static java.util.Set<String> canonicalCapabilities() {
        return java.util.Collections.unmodifiableSet(CANONICAL_CAPS);
    }
```

> `elevator:view` 全角色 C（§4.2「全部角色△」），故无 Y 持有者但有 C 持有者，通过 hasHolder 校验。`DATA_EXPORT_SENSITIVE` 的「+审批」是 M5 工作流，M1 编码平台 Y（能力持有），审批门控不在本期。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=PermissionMatrixV2Test,RbacMatrixTest`
Expected: PASS（86 码、三态样本、fail-closed 全过；RbacMatrixTest 字面不动仍绿）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/auth/PermissionMatrix.java backend/src/test/java/com/cqwlw/maintenance/auth/PermissionMatrixV2Test.java
git commit -m "feat: 权限矩阵 v2 八十六码三态唯一事实源"
```

---

### Task 6: LegacyCapabilityTranslator（24→71 + 门控 + 反转）

**Files:**
- Create: `auth/LegacyCapabilityTranslator.java`
- Modify: `auth/PermissionMatrix.java`（`allows(role,旧码)` 委托翻译器，行为不变）
- Test: `backend/src/test/java/com/cqwlw/maintenance/auth/LegacyCapabilityTranslatorTest.java`

**Interfaces:**
- Consumes: `PermissionMatrix.state/allowsNew`（Task 5）、`RoleCatalog.canonical/canonicalRole`（Task 4）、冻结 `GRANTS`（经**本任务新增**的 `holders` 访问器只读；现状 `grant(Map,String,String...)` 是 private void 构建器，非 public getter）。
- Produces:
  - `PermissionMatrix.holders(String oldCode)→Set<String>` — **新增 public 冻结授权访问器**（现状 `grant(Map,String,String...)` 是 private void 构建器，无 public getter；翻译器与测试需要只读访问冻结 GRANTS）。未知码抛 IllegalArgumentException。
  - `static Set<String> translate(String oldCode)` — 旧码→新码集（M0 §2.1；未知旧码抛 IllegalArgumentException）。
  - `static boolean allows(String role, String oldCode)` — 旧别名二值判定，**精确复现冻结 GRANTS**；console:read 经 adminConsole 门控计算且结果==冻结集。
  - `PermissionMatrix.allows(role,旧码)` 改为 `requireKnownCapability(旧码); return LegacyCapabilityTranslator.allows(role,旧码);`（结果与现状逐字相同）。

- [ ] **Step 1: Write the failing test**

`LegacyCapabilityTranslatorTest.java`：

```java
package com.cqwlw.maintenance.auth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 翻译器：24→71 映射、console:read adminConsole 门控、order/fault 反转、复现 RbacMatrixTest 冻结授权。 */
class LegacyCapabilityTranslatorTest {

    @Test
    void orderFaultSemanticReversal() {
        // 旧 order:*（维保工单）→ 新 plan/task/checklist/record，绝不映射到新 order:*
        Set<String> orderRead = LegacyCapabilityTranslator.translate("order:read");
        assertTrue(orderRead.contains("plan:view"));
        assertTrue(orderRead.contains("record:view"));
        assertFalse(orderRead.contains("order:view_pool"), "旧 order:read 不得映射新 order 域");
        // 旧 fault:*（急修单）→ 新 order:*
        Set<String> faultRead = LegacyCapabilityTranslator.translate("fault:read");
        assertTrue(faultRead.contains("order:view_pool"));
        assertTrue(faultRead.contains("order:view_own"));
        assertFalse(faultRead.contains("plan:view"));
    }

    @Test
    void fourCriticalCodesMapping() {
        assertTrue(LegacyCapabilityTranslator.translate("console:write").contains("config:manage"));
        assertTrue(LegacyCapabilityTranslator.translate("plan:write").contains("plan:generate"));
        assertTrue(LegacyCapabilityTranslator.translate("region:write").contains("region:write"));
        // console:read 无独立新码（被各域 view 吸收），translate 返回空集，判定走门控
        assertTrue(LegacyCapabilityTranslator.translate("console:read").isEmpty());
    }

    @Test
    void consoleReadGateMatchesFrozenGrant() {
        // 冻结 CONSOLE_READ = {ADMIN,SA,INSTALL_MANAGER,INSTALL_CLERK,MAINT_MANAGER,MAINT_BIZ,LEADER,MAINT_QUALITY}
        Set<String> frozen = PermissionMatrix.holders("console:read");
        for (String role : RoleCatalog.codes()) {
            assertEquals(frozen.contains(role), LegacyCapabilityTranslator.allows(role, "console:read"),
                    "console:read 门控不符: " + role);
        }
        // worker/门户 登录管理端即拒
        assertFalse(LegacyCapabilityTranslator.allows("WORKER", "console:read"));
        assertFalse(LegacyCapabilityTranslator.allows("INSTALL_WORKER", "console:read"));
        assertFalse(LegacyCapabilityTranslator.allows("UNIT_ADMIN", "console:read"));
    }

    @Test
    void reproducesFrozenGrantsForAll24x12() {
        // RbacMatrixTest 是唯一 oracle：翻译器 allows 必须与冻结 GRANTS 逐格相同
        for (String cap : PermissionMatrix.capabilities()) {
            Set<String> frozen = PermissionMatrix.holders(cap);
            for (String role : RoleCatalog.codes()) {
                assertEquals(frozen.contains(role), LegacyCapabilityTranslator.allows(role, cap),
                        cap + " × " + role);
            }
        }
    }

    @Test
    void unknownOldCodeFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> LegacyCapabilityTranslator.translate("order:write2"));
        assertFalse(LegacyCapabilityTranslator.allows("HACKER", "console:read"));
        assertFalse(LegacyCapabilityTranslator.allows("ADMIN", null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=LegacyCapabilityTranslatorTest`
Expected: FAIL — 编译错误（LegacyCapabilityTranslator 不存在）。

- [ ] **Step 3: Implement the translator**

`auth/LegacyCapabilityTranslator.java`：

```java
package com.cqwlw.maintenance.auth;

import java.util.Map;
import java.util.Set;

/**
 * 旧 24 码 → 新 71+ 码翻译（M0 §2.1 唯一映射源）。
 * 兼容期二值判定 {@link #allows} 精确复现冻结 GRANTS（RbacMatrixTest oracle）；
 * console:read 经 adminConsole 门控计算（M1-4），order/fault 语义反转（M0 §2.0，Blocker）。
 * 新码三态事实源在 PermissionMatrix.STATES；本类只做桥接，不维护第二份授权事实。
 */
public final class LegacyCapabilityTranslator {

    private LegacyCapabilityTranslator() {
    }

    /** 旧码→新码集（M0 §2.1 主映射）。console:read 无独立新码→空集（判定走门控）。 */
    private static final Map<String, Set<String>> MAP = Map.ofEntries(
            Map.entry("console:read", Set.of()),
            Map.entry("console:write", Set.of("config:manage")),
            Map.entry("order:read", Set.of("plan:view", "record:view", "task:accept")),
            Map.entry("order:write", Set.of("task:accept", "checklist:fill", "record:submit", "record:sign_tech")),
            Map.entry("fault:read", Set.of("order:view_pool", "order:view_own")),
            Map.entry("fault:write", Set.of("order:create", "order:dispatch", "order:arrive", "order:process",
                    "order:submit", "order:confirm", "order:close", "order:cancel")),
            Map.entry("record:read", Set.of("record:view")),
            Map.entry("report:retry", Set.of("report:retry")),
            Map.entry("plan:write", Set.of("plan:generate", "task:assign_partner")),
            Map.entry("template:write", Set.of("template:write", "template:toggle")),
            Map.entry("ledger:read", Set.of("elevator:view", "project:view")),
            Map.entry("ledger:write", Set.of("elevator:edit", "elevator:transfer")),
            Map.entry("employee:read", Set.of("user:view")),
            Map.entry("employee:write", Set.of("user:create", "user:disable", "user:reset_password")),
            Map.entry("contract:read", Set.of("contract:view")),
            Map.entry("contract:write", Set.of("contract:edit")),
            Map.entry("useunit:read", Set.of("client:elevator_view")),
            Map.entry("useunit:write", Set.of("client:edit")),
            Map.entry("handover:read", Set.of("handover:view")),
            Map.entry("handover:write", Set.of("handover:create", "handover:edit", "handover:submit",
                    "handover:doc_upload", "handover:doc_delete", "handover:review", "handover:receive",
                    "handover:inspect", "handover:rectify", "handover:sign_install", "handover:sign_maint", "handover:void")),
            Map.entry("filing:read", Set.of("filing:view")),
            Map.entry("filing:write", Set.of("filing:write")),
            Map.entry("region:write", Set.of("region:view", "region:write")),
            Map.entry("account:manage", Set.of("user:create", "user:disable", "invite:manage"))
    );

    public static Set<String> translate(String oldCode) {
        Set<String> mapped = MAP.get(oldCode);
        if (mapped == null) {
            throw new IllegalArgumentException("未知旧权限码: " + oldCode);
        }
        return mapped;
    }

    /**
     * 旧别名二值判定（兼容期）。冻结 GRANTS 是 oracle；console:read 用 adminConsole 门控
     * 计算并断言与冻结集一致。未知角色→false，未知/null 码→false（与 PermissionMatrix.allows 现状一致）。
     */
    public static boolean allows(String role, String oldCode) {
        if (role == null || oldCode == null || !MAP.containsKey(oldCode)) {
            return false;
        }
        if ("console:read".equals(oldCode)) {
            return consoleReadGate(role);
        }
        // 其余 23 码：冻结授权（M1 保留旧行为，目标态差异推迟 M5）
        return PermissionMatrix.holders(oldCode).contains(role);
    }

    /** console:read(role) = adminConsole(role) ∧ 任一域 view∈{Y,C}（M1-4）。 */
    private static boolean consoleReadGate(String role) {
        if (!RoleCatalog.canUseAdminConsole(role)) {
            return false;
        }
        String canonical = RoleCatalog.canonical(role);
        if (canonical == null) {
            return false;
        }
        for (String code : PermissionMatrix.canonicalCapabilities()) {
            if (code.endsWith(":view") || code.endsWith(":view_pool") || code.endsWith(":view_own")) {
                PermissionMatrix.TriState st = PermissionMatrix.state(canonical, code);
                if (st == PermissionMatrix.TriState.Y || st == PermissionMatrix.TriState.C) {
                    return true;
                }
            }
        }
        return false;
    }
}
```

> `consoleReadGate` 对 8 个旧 Web 角色（ADMIN/SA/INSTALL_MANAGER/INSTALL_CLERK/MAINT_MANAGER/MAINT_BIZ/LEADER/MAINT_QUALITY）均返回 true（各自持有域 view 码），对 UNIT_ADMIN 返回 false（canUseAdminConsole=false），精确等于冻结 CONSOLE_READ。测试 `consoleReadGateMatchesFrozenGrant` 与 `reproducesFrozenGrantsForAll24x12` 守门。

在 `PermissionMatrix.java` 追加 public 冻结授权访问器 `holders`（现状只有 private void 构建器 `grant(Map,String,String...)`，无 public getter；翻译器与测试需只读访问冻结 GRANTS），并把 `allows` 方法体改为委托翻译器（**签名/语义逐字不变**）：

```java
    /** 冻结旧授权持有者集（兼容期 oracle，RbacMatrixTest 锁定）；未知/null 码抛 IllegalArgumentException。 */
    public static Set<String> holders(String capability) {
        requireKnownCapability(capability);
        return GRANTS.getOrDefault(capability, Set.of());
    }

    public static boolean allows(String role, String capability) {
        requireKnownCapability(capability); // 未知/null 码抛 IllegalArgumentException（保持现状，RbacMatrixTest 锁定）
        return LegacyCapabilityTranslator.allows(role, capability);
    }
```

> 现状 `allows`＝`requireKnownCapability(cap)` + null 角色返 false + `GRANTS.get(cap).contains(role)`。委托后：`requireKnownCapability` 仍先抛未知码异常；翻译器 `allows` 对 null/未知角色返 false、对其余 23 码返 `holders(cap).contains(role)`（＝原 GRANTS 查询）、对 console:read 走门控（结果==冻结集）。逐格等价，`RbacMatrixTest` 24×12 与 `unknownCapabilityFailsLoudly` 全绿。`holders` 返回 `Map.copyOf` 的不可变集，调用方只读。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=LegacyCapabilityTranslatorTest,RbacMatrixTest,AdminRoleInterceptorCompatTest,AdminRoleInterceptorTest`
Expected: PASS（翻译器全过；RbacMatrixTest 24×12 精确授权不变；CompatTest 5×17 与 AdminRoleInterceptorTest 字面不动仍绿）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/auth/LegacyCapabilityTranslator.java backend/src/main/java/com/cqwlw/maintenance/auth/PermissionMatrix.java backend/src/test/java/com/cqwlw/maintenance/auth/LegacyCapabilityTranslatorTest.java
git commit -m "feat: 旧码翻译兼容层（adminConsole 门控与 order/fault 反转）"
```

---

### Task 7: JwtService scopeVersion claim + 登录发证

**Files:**
- Modify: `auth/JwtService.java`（issue 6 参 + sv claim）、`auth/AuthController.java`（issueSessionToken 传 scopeVersion）
- Modify: `backend/src/test/java/com/cqwlw/maintenance/auth/UnifiedLoginTest.java`（issue mock 改 6 参）
- Test: `backend/src/test/java/com/cqwlw/maintenance/auth/JwtServiceSvTest.java`

**Interfaces:**
- Consumes: `AppProperties.getJwtSecret()`/`getExpireHours()`；`Employee.scopeVersion`（Task 3）。
- Produces: `JwtService.issue(String employeeId, String role, String openid, String client, String sessionId, int scopeVersion)→String`（6 参，claims 增 `sv`）；**保留 5 参重载**委托 6 参（sv=0），`JwtServiceTest` 字面不动；`verify(token)→Claims`（可读 `sv`）。`issueUnit` 不变。AuthController:232 改调 6 参传 `user.scopeVersion`。

- [ ] **Step 1: Write the failing test**

`JwtServiceSvTest.java`：

```java
package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.config.AppProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JWT sv claim：发证携带 scopeVersion，verify 可读回。 */
class JwtServiceSvTest {

    private JwtService service() {
        AppProperties props = new AppProperties();
        props.setJwtSecret("test-secret-key-for-jwt-sv-claim-0123456789");
        return new JwtService(props);
    }

    @Test
    void issueCarriesScopeVersion() {
        JwtService jwt = service();
        String token = jwt.issue("emp_1", "WORKER", null, "mp", "sess_1", 7);
        Claims claims = jwt.verify(token);
        assertEquals("emp_1", claims.getSubject());
        assertEquals(7, ((Number) claims.get("sv")).intValue());
    }

    @Test
    void zeroScopeVersionIsCarried() {
        JwtService jwt = service();
        Claims claims = jwt.verify(jwt.issue("emp_1", "ADMIN", null, "admin", "sess_1", 0));
        assertEquals(0, ((Number) claims.get("sv")).intValue());
    }
}
```

> `AppProperties.setJwtSecret(String)`/`getJwtSecret()`/`getJwtExpireHours()`（默认 72）已核对源码确认存在；`JwtService` 构造器读 `getJwtSecret()`（null/空白即抛错）。测试 secret 为 ≥32 字节占位串，**非真实凭证**（AGENTS §2 红线：真实 JWT_SECRET 不入库）。

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=JwtServiceSvTest`
Expected: FAIL — 编译错误（issue 现 5 参，无 6 参重载）。

- [ ] **Step 3: Implement sv claim**

`auth/JwtService.java`：现有 `issue` 用 `Map<String,Object> claims` + `.claims(claims)` 构建（**非**链式 `.claim()`）。新增 6 参方法（claims 增 `sv`），并**保留 5 参重载**委托 6 参（sv=0）——`JwtServiceTest.java:23` 仍调 5 参，保留重载使其字面不动仍绿：

```java
    /** 5 参兼容重载（JwtServiceTest 锁定）：scopeVersion 默认 0，与 pre-M1 旧 token 兼容口径一致。 */
    public String issue(String employeeId, String role, String openid, String client, String sessionId) {
        return issue(employeeId, role, openid, client, sessionId, 0);
    }

    public String issue(String employeeId, String role, String openid, String client, String sessionId, int scopeVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("client", client);
        claims.put("sid", sessionId);
        claims.put("sv", scopeVersion);
        if (openid != null) {
            claims.put("openid", openid);
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(employeeId)
                .claims(claims)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireHours * 3600)))
                .signWith(key)
                .compact();
    }
```

> `issueUnit`/`verify` 不动。`AppProperties.setJwtSecret(String)`/`getJwtExpireHours()`（默认 72）已确认存在，JwtServiceSvTest 的 `new JwtService(props)` 可正常签发/校验（secret 为 ≥32 字节测试占位串，非真实凭证）。

`auth/AuthController.java`：`issueSessionToken` 末尾改为传 `user.scopeVersion`（null 安全）：

```java
        int sv = user.scopeVersion == null ? 0 : user.scopeVersion;
        return jwtService.issue(user.id, role, openid, client, sid, sv);
```

`auth/UnifiedLoginTest.java`：把 `employeeLoginUnchanged` 里的 5 参 mock 改 6 参（增 `anyInt()`）：

```java
        when(jwtService.issue(anyString(), anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn("emp-token");
```

并在 import 增 `import static org.mockito.ArgumentMatchers.anyInt;`。其余断言不动。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=JwtServiceSvTest,UnifiedLoginTest,JwtServiceTest`
Expected: PASS（sv claim 携带/读回；UnifiedLoginTest 6 参 mock 后仍绿；JwtServiceTest 经 5 参重载字面不动仍绿）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/auth/JwtService.java backend/src/main/java/com/cqwlw/maintenance/auth/AuthController.java backend/src/test/java/com/cqwlw/maintenance/auth/UnifiedLoginTest.java backend/src/test/java/com/cqwlw/maintenance/auth/JwtServiceSvTest.java
git commit -m "feat: JWT 增 scopeVersion claim，登录发证携带"
```

---

### Task 8: AuthInterceptor v2（sv 校验 + ATTR_ROLES + 门户别名）

**Files:**
- Modify: `auth/AuthInterceptor.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/auth/AuthInterceptorV2Test.java`

**Interfaces:**
- Consumes: `JwtService.verify`（sv claim，Task 7）、`EmployeeMapper.selectById`/`bumpScopeVersion`、`SysUserRoleMapper.selectList`（Task 3）、`RoleCatalog.canonical`（Task 4）。
- Produces:
  - 新常量 `public static final String ATTR_ROLES = "roles";`（规范码集 `Set<String>`）。
  - 3 参 `@Autowired AuthInterceptor(JwtService, EmployeeMapper, SysUserRoleMapper)`；2 参 `AuthInterceptor(JwtService, EmployeeMapper)` 委托 `this(jwt, mapper, null)`。
  - 员工分支：sid 校验后增 sv 校验（null 安全）；ATTR_ROLES 由 sys_user_role 填（mapper 为 null 时回退主角色规范码单元素集）。
  - 门户分支：ATTR_ROLE 仍 "UNIT_ADMIN"（UnitTokenScopeTest 锁定），ATTR_ROLES 含 CLIENT_ADMIN（+UNIT_ADMIN 别名）。

- [ ] **Step 1: Write the failing test**

`AuthInterceptorV2Test.java`：

```java
package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysUserRole;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysUserRoleMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AuthInterceptor v2：sv 校验（null 安全）、ATTR_ROLES、门户 CLIENT_ADMIN 别名。 */
class AuthInterceptorV2Test {

    private final JwtService jwt = mock(JwtService.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final Object handler = new Object();

    private HttpServletRequest request(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("GET");
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getHeader("Authorization")).thenReturn("Bearer token");
        return req;
    }

    private Claims claims(String sub, String role, String client, String sid, Integer sv) {
        Claims c = mock(Claims.class);
        when(c.getSubject()).thenReturn(sub);
        when(c.get("role", String.class)).thenReturn(role);
        when(c.get("client", String.class)).thenReturn(client);
        when(c.get("sid", String.class)).thenReturn(sid);
        when(c.get("sv")).thenReturn(sv);
        when(c.get("ut", String.class)).thenReturn(null);
        return c;
    }

    private Employee employee(String id, String role, String sid, Integer sv) {
        Employee e = new Employee();
        e.id = id;
        e.role = role;
        e.sessionId = sid;
        e.scopeVersion = sv;
        return e;
    }

    @Test
    void scopeVersionMismatchRejects() {
        AuthInterceptor interceptor = new AuthInterceptor(jwt, employeeMapper, userRoleMapper);
        when(jwt.verify(anyString())).thenReturn(claims("emp_1", "WORKER", "mp", "s1", 3));
        when(employeeMapper.selectById("emp_1")).thenReturn(employee("emp_1", "WORKER", "s1", 5));
        BizException e = assertThrows(BizException.class,
                () -> interceptor.preHandle(request("/work-orders"), response, handler));
        assertEquals(401, e.getCode());
    }

    @Test
    void legacyTokenWithoutSvMatchesDefaultZero() {
        AuthInterceptor interceptor = new AuthInterceptor(jwt, employeeMapper, userRoleMapper);
        when(jwt.verify(anyString())).thenReturn(claims("emp_1", "WORKER", "mp", "s1", null));
        when(employeeMapper.selectById("emp_1")).thenReturn(employee("emp_1", "WORKER", "s1", null));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        HttpServletRequest req = request("/work-orders");
        assertTrue(interceptor.preHandle(req, response, handler));
        assertEquals("WORKER", req.getAttribute(AuthInterceptor.ATTR_ROLE));
    }

    @Test
    void multiRoleSetPopulatedFromUserRoles() {
        AuthInterceptor interceptor = new AuthInterceptor(jwt, employeeMapper, userRoleMapper);
        when(jwt.verify(anyString())).thenReturn(claims("emp_1", "LEADER", "mp", "s1", 0));
        when(employeeMapper.selectById("emp_1")).thenReturn(employee("emp_1", "LEADER", "s1", 0));
        SysUserRole r1 = new SysUserRole(); r1.roleCode = "MAINT_SUP";
        SysUserRole r2 = new SysUserRole(); r2.roleCode = "MAINT_TECH";
        when(userRoleMapper.selectList(any())).thenReturn(List.of(r1, r2));
        HttpServletRequest req = request("/work-orders");
        assertTrue(interceptor.preHandle(req, response, handler));
        assertEquals("LEADER", req.getAttribute(AuthInterceptor.ATTR_ROLE)); // 主角色旧码逐字不变
        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) req.getAttribute(AuthInterceptor.ATTR_ROLES);
        assertEquals(Set.of("MAINT_SUP", "MAINT_TECH"), roles);
    }

    @Test
    void twoArgConstructorFallsBackToPrimaryRole() {
        AuthInterceptor interceptor = new AuthInterceptor(jwt, employeeMapper); // 2 参委托
        when(jwt.verify(anyString())).thenReturn(claims("emp_1", "WORKER", "mp", "s1", 0));
        when(employeeMapper.selectById("emp_1")).thenReturn(employee("emp_1", "WORKER", "s1", 0));
        HttpServletRequest req = request("/work-orders");
        assertTrue(interceptor.preHandle(req, response, handler));
        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) req.getAttribute(AuthInterceptor.ATTR_ROLES);
        assertEquals(Set.of("MAINT_TECH"), roles); // 回退主角色规范码
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=AuthInterceptorV2Test`
Expected: FAIL — 编译错误（ATTR_ROLES / 3 参构造器不存在）。

- [ ] **Step 3: Implement AuthInterceptor v2**

`auth/AuthInterceptor.java`：增常量与构造器，改 preHandle 员工/门户分支。

```java
    public static final String ATTR_ROLES = "roles";

    private final JwtService jwtService;
    private final com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper;
    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper; // 可为 null（2 参兼容）

    @org.springframework.beans.factory.annotation.Autowired
    public AuthInterceptor(JwtService jwtService,
                           com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper,
                           com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper) {
        this.jwtService = jwtService;
        this.employeeMapper = employeeMapper;
        this.userRoleMapper = userRoleMapper;
    }

    /** 2 参兼容构造器（AuthInterceptorTest/UnitTokenScopeTest 锁定）：无 sys_user_role 查询，回退主角色。 */
    public AuthInterceptor(JwtService jwtService,
                           com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper) {
        this(jwtService, employeeMapper, null);
    }
```

门户分支（ut=1）：在现有 `request.setAttribute(ATTR_ROLE, "UNIT_ADMIN")` 之后增：

```java
            request.setAttribute(ATTR_ROLES, java.util.Set.of("CLIENT_ADMIN", "UNIT_ADMIN"));
```

员工分支：在 sid 校验通过、`request.setAttribute(ATTR_EMP_ID, ...)` 之前增 sv 校验；在 setAttribute(ATTR_ROLE/ATTR_OPENID) 之后增 ATTR_ROLES：

```java
        // scopeVersion 校验（spec §4.2）：旧 token sv=null→0，DB null→0，不误拒
        int tokenSv = claims.get("sv") == null ? 0 : ((Number) claims.get("sv")).intValue();
        int dbSv = user.scopeVersion == null ? 0 : user.scopeVersion;
        if (tokenSv != dbSv) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        request.setAttribute(ATTR_EMP_ID, claims.getSubject());
        request.setAttribute(ATTR_ROLE, user.role);
        request.setAttribute(ATTR_OPENID, claims.get("openid"));
        request.setAttribute(ATTR_ROLES, resolveRoles(user));
```

新增私有方法：

```java
    /** 多角色规范码集：有 sys_user_role 取其 role_code 规范码；否则回退主角色规范码（2 参兼容）。 */
    private java.util.Set<String> resolveRoles(com.cqwlw.maintenance.entity.Employee user) {
        java.util.Set<String> roles = new java.util.LinkedHashSet<>();
        if (userRoleMapper != null) {
            for (com.cqwlw.maintenance.entity.SysUserRole ur : userRoleMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cqwlw.maintenance.entity.SysUserRole>()
                            .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserId, user.id)
                            .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserType, "EMPLOYEE"))) {
                String c = RoleCatalog.canonical(ur.roleCode);
                if (c != null) {
                    roles.add(c);
                }
            }
        }
        if (roles.isEmpty()) {
            String c = RoleCatalog.canonical(user.role);
            if (c != null) {
                roles.add(c);
            }
        }
        return roles;
    }
```

> GET `/files/*` bypass、unit 路径限制（/unit/+/files/）、sid 会话校验、ATTR_ROLE 单值均保持现状不动。`SysUserRole::getUserId`/`getUserType` 由 Lombok @Data 生成（Task 3）。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=AuthInterceptorV2Test,AuthInterceptorTest,UnitTokenScopeTest`
Expected: PASS（v2 新断言全过；AuthInterceptorTest 2 参 + UnitTokenScopeTest ATTR_ROLE=="UNIT_ADMIN" 字面不动仍绿）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/auth/AuthInterceptor.java backend/src/test/java/com/cqwlw/maintenance/auth/AuthInterceptorV2Test.java
git commit -m "feat: 鉴权链 v2 多角色集与 scopeVersion 校验"
```

---

### Task 9: ScopeResolver + V7 等价证明

**Files:**
- Create: `service/ScopeResolver.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/service/ScopeResolverEquivalenceTest.java`

**Interfaces:**
- Consumes: `EmployeeScopeService.visibleReadPlatformIds(Employee)`/`visibleWorkerPlatformIds(Employee)`（不动）、`DeptScopeService.useUnitScope(Employee)`（不动）、`SysRoleScopeMapper.selectById`（Task 3）、`RoleCatalog.canonical`。
- Produces:
  - `ScopeResolver(EmployeeScopeService, DeptScopeService, SysRoleScopeMapper)`（@Service 3 参）。
  - `Set<String> readPlatformIds(Employee e)` — 旧角色委托 visibleReadPlatformIds；新角色 INST_MGR→部门全量(null)、CLIENT_SIGNER→本单位（门户谓词）。
  - `Set<String> writePlatformIds(Employee e)` — 旧角色委托 visibleWorkerPlatformIds；新角色严格本人/本单位。
  - `String unitScope(Employee e)` — 委托 deptScope.useUnitScope。
  - `String defaultScope(String canonicalRole)` — 读 sys_role_scope.scope（未知 null）。

- [ ] **Step 1: Write the failing test**

`ScopeResolverEquivalenceTest.java`（等价靶＝EmployeeScopeService，逐集合相等；代表班组/区域配置）：

```java
package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysRoleScopeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ScopeResolver 与 V7 谓词逐集合相等（spec §4.5）；读写不对称保留；写绝不扩区域/部门。 */
class ScopeResolverEquivalenceTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final EmployeeScopeService v7 = new EmployeeScopeService(employeeMapper);
    private final DeptScopeService dept = new DeptScopeService(employeeMapper);
    private final ScopeResolver resolver =
            new ScopeResolver(v7, dept, mock(SysRoleScopeMapper.class));

    private Employee emp(String id, String role, String platformId, String regionId, String groupName) {
        Employee e = new Employee();
        e.id = id; e.role = role; e.platformId = platformId; e.regionId = regionId; e.groupName = groupName;
        return e;
    }

    @Test
    void readWriteEquivalenceForAllLegacyEmployeeRoles() {
        // 区域 r1 含 p1/p2；班组 g1 含 p1/p3
        Employee r1m1 = emp("e1", "WORKER", "p1", "r1", "g1");
        Employee r1m2 = emp("e2", "WORKER", "p2", "r1", "g2");
        Employee g1m3 = emp("e3", "WORKER", "p3", "r2", "g1");
        when(employeeMapper.selectList(any())).thenReturn(List.of(r1m1, r1m2, g1m3));

        String[] roles = {"ADMIN", "SYS_ADMIN", "MAINT_MANAGER", "MAINT_QUALITY", "LEADER", "WORKER",
                "ASSISTANT", "INSTALL_MANAGER", "INSTALL_CLERK", "INSTALL_WORKER", "MAINT_BIZ"};
        for (String role : roles) {
            Employee e = emp("self", role, "p_self", "r1", "g1");
            assertEquals(v7.visibleReadPlatformIds(e), resolver.readPlatformIds(e), "读不等价: " + role);
            assertEquals(v7.visibleWorkerPlatformIds(e), resolver.writePlatformIds(e), "写不等价: " + role);
        }
    }

    @Test
    void writeNeverExpandsToRegionOrDepartment() {
        Employee r1m1 = emp("e1", "WORKER", "p1", "r1", "g1");
        Employee r1m2 = emp("e2", "WORKER", "p2", "r1", "g2");
        when(employeeMapper.selectList(any())).thenReturn(List.of(r1m1, r1m2));
        // MAINT_MANAGER 读=null（部门全量），写=本人（绝不 null）
        Employee mm = emp("mm", "MAINT_MANAGER", "p_mm", "r1", "g1");
        assertEquals(null, resolver.readPlatformIds(mm));
        assertEquals(Set.of("p_mm"), resolver.writePlatformIds(mm));
        // WORKER 读含区域 p1/p2/p_self，写仅本人
        Employee w = emp("w", "WORKER", "p_self", "r1", "g1");
        assertEquals(Set.of("p_self"), resolver.writePlatformIds(w));
    }

    @Test
    void portalUnitScopeConvergesToBoundUnit() {
        Employee unit = emp("u1", "UNIT_ADMIN", null, null, null);
        // DeptScopeService 默认 NO_BINDING→null；等价靶即 useUnitScope 输出
        assertEquals(dept.useUnitScope(unit), resolver.unitScope(unit));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=ScopeResolverEquivalenceTest`
Expected: FAIL — 编译错误（ScopeResolver 不存在）。

- [ ] **Step 3: Implement ScopeResolver**

`service/ScopeResolver.java`：

```java
package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.SysRoleScope;
import com.cqwlw.maintenance.mapper.SysRoleScopeMapper;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 6 范围读写双谓词门面（spec §4.4）。旧角色委托 EmployeeScopeService（V7 不动，证等价）；
 * 新角色 INST_MGR(ORG)/CLIENT_SIGNER(ORG_FLAT) 按 sys_role_scope 默认范围解析，不在 V7 等价靶内。
 * 读写分离红线：范围扩大只进读谓词，写严格本人/本组/被指派（AGENTS §3.1）。
 */
@Service
public class ScopeResolver {

    private final EmployeeScopeService employeeScope;
    private final DeptScopeService deptScope;
    private final SysRoleScopeMapper roleScopeMapper;

    public ScopeResolver(EmployeeScopeService employeeScope, DeptScopeService deptScope,
                         SysRoleScopeMapper roleScopeMapper) {
        this.employeeScope = employeeScope;
        this.deptScope = deptScope;
        this.roleScopeMapper = roleScopeMapper;
    }

    public Set<String> readPlatformIds(Employee e) {
        String canonical = RoleCatalog.canonical(e.role);
        if ("INST_MGR".equals(canonical)) {
            return null; // ORG：安装部及下级全量读（新角色，无 V7 对应）
        }
        return employeeScope.visibleReadPlatformIds(e);
    }

    public Set<String> writePlatformIds(Employee e) {
        String canonical = RoleCatalog.canonical(e.role);
        if ("INST_MGR".equals(canonical)) {
            // 写仍严格本人（防代签到/代签退）；ORG 只扩读
            return e.platformId == null || e.platformId.isBlank() ? Set.of() : Set.of(e.platformId);
        }
        return employeeScope.visibleWorkerPlatformIds(e);
    }

    /** 门户域本单位收敛（CLIENT_ADMIN/CLIENT_SIGNER）；委托 DeptScopeService.useUnitScope。 */
    public String unitScope(Employee e) {
        return deptScope.useUnitScope(e);
    }

    /** 角色默认范围枚举（sys_role_scope）；未知 null。 */
    public String defaultScope(String canonicalRole) {
        if (canonicalRole == null) {
            return null;
        }
        SysRoleScope rs = roleScopeMapper.selectById(canonicalRole);
        return rs == null ? null : rs.scope;
    }
}
```

> 等价证明 by delegation：旧角色 read/write 直接返回 V7 输出，逐集合相等由构造保证，测试守未来漂移。新角色 INST_MGR 读 null/写本人，CLIENT_SIGNER 走门户 unitScope（ORG_FLAT），均不在旧角色等价靶内（spec §4.4）。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=ScopeResolverEquivalenceTest`
Expected: PASS（11 旧角色读/写逐集合相等；读写不对称；门户收敛）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/service/ScopeResolver.java backend/src/test/java/com/cqwlw/maintenance/service/ScopeResolverEquivalenceTest.java
git commit -m "feat: ScopeResolver 读写双谓词与 V7 等价证明"
```

---

### Task 10: GrantService 限时授权 + 到期回收

**Files:**
- Create: `service/GrantService.java`
- Test: `backend/src/test/java/com/cqwlw/maintenance/service/GrantServiceTest.java`

**Interfaces:**
- Consumes: `SysGrantMapper`（Task 3）、`EmployeeMapper.bumpScopeVersion`（Task 3）、`AuditLogService.append`（Task 11）、`TimeUtil.now()`、`Ids.next("grant")`。
- Produces:
  - `GrantService(SysGrantMapper, EmployeeMapper, AuditLogService)`（@Service 3 参）。
  - `SysGrant create(String granteeId, String permCode, String scope, String scopeRef, LocalDateTime start, LocalDateTime end, String grantedBy, String reason)` — 校验 end>start，落 PENDING/ACTIVE（按 start），写审计。
  - `void withdraw(String grantId, String actorId)` — ACTIVE/PENDING→WITHDRAWN + bumpScopeVersion + 审计。
  - `int revokeExpired()` — @Scheduled：ACTIVE∧end≤now→REVOKED + bumpScopeVersion + 审计；PENDING∧start≤now→ACTIVE；返回处理行数。
  - `Set<String> activePermCodes(String granteeId)` — 当前 ACTIVE 授权 perm_code 并集（解析集成）。

- [ ] **Step 1: Write the failing test**

`GrantServiceTest.java`：

```java
package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.SysGrant;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysGrantMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 限时授权：end>start、四态生命周期、回收递增 scope_version+审计、解析并集。 */
class GrantServiceTest {

    private final SysGrantMapper grantMapper = mock(SysGrantMapper.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final GrantService service = new GrantService(grantMapper, employeeMapper, audit);

    @Test
    void endMustBeAfterStart() {
        LocalDateTime now = TimeUtil.now();
        assertThrows(BizException.class, () -> service.create("emp_1", "order:dispatch", null, null,
                now, now.minusDays(1), "emp_mgr", "支援"));
    }

    @Test
    void futureGrantIsPendingPastGrantIsActive() {
        LocalDateTime now = TimeUtil.now();
        SysGrant future = service.create("emp_1", "order:dispatch", null, null,
                now.plusDays(1), now.plusDays(2), "emp_mgr", "支援");
        assertEquals("PENDING", future.status);
        SysGrant active = service.create("emp_1", "order:dispatch", null, null,
                now.minusDays(1), now.plusDays(1), "emp_mgr", "支援");
        assertEquals("ACTIVE", active.status);
        verify(grantMapper, atLeastOnce()).insert(any(SysGrant.class));
    }

    @Test
    void revokeExpiredSetsRevokedAndBumpsScopeVersion() {
        LocalDateTime now = TimeUtil.now();
        SysGrant expired = new SysGrant();
        expired.id = "g1"; expired.granteeId = "emp_1"; expired.status = "ACTIVE";
        expired.endAt = now.minusMinutes(1);
        SysGrant pending = new SysGrant();
        pending.id = "g2"; pending.granteeId = "emp_2"; pending.status = "PENDING";
        pending.startAt = now.minusMinutes(1); pending.endAt = now.plusDays(1);
        when(grantMapper.selectList(any())).thenReturn(List.of(expired, pending));

        int n = service.revokeExpired();
        assertEquals(2, n);
        assertEquals("REVOKED", expired.status);
        assertEquals("ACTIVE", pending.status);
        verify(employeeMapper).bumpScopeVersion("emp_1"); // 回收递增被授权人 scope_version
        verify(audit, atLeastOnce()).append(any());
    }

    @Test
    void activePermCodesUnion() {
        SysGrant g = new SysGrant();
        g.granteeId = "emp_1"; g.permCode = "order:dispatch"; g.status = "ACTIVE";
        when(grantMapper.selectList(any())).thenReturn(List.of(g));
        assertTrue(service.activePermCodes("emp_1").contains("order:dispatch"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=GrantServiceTest`
Expected: FAIL — 编译错误（GrantService 不存在）。

- [ ] **Step 3: Implement GrantService**

`service/GrantService.java`：

```java
package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.entity.SysGrant;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysGrantMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 限时授权（spec §5.1）：PENDING→ACTIVE→REVOKED/WITHDRAWN，到期自动回收 + 递增 scope_version + 审计。
 * 回收是授权时效，与 AGENTS §2.4「上报无自动重试」无关；不触派单（AGENTS §3.6）。
 * 发起/撤回管理端点随 M3 #/grants；M1 交付机制 + 测试夹具。
 */
@Service
public class GrantService {

    private static final Logger log = LoggerFactory.getLogger(GrantService.class);

    private final SysGrantMapper grantMapper;
    private final EmployeeMapper employeeMapper;
    private final AuditLogService auditLogService;

    public GrantService(SysGrantMapper grantMapper, EmployeeMapper employeeMapper,
                        AuditLogService auditLogService) {
        this.grantMapper = grantMapper;
        this.employeeMapper = employeeMapper;
        this.auditLogService = auditLogService;
    }

    public SysGrant create(String granteeId, String permCode, String scope, String scopeRef,
                           LocalDateTime start, LocalDateTime end, String grantedBy, String reason) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new BizException(422, "授权结束时间必须晚于开始时间");
        }
        LocalDateTime now = TimeUtil.now();
        SysGrant g = new SysGrant();
        g.id = Ids.next("grant");
        g.granteeId = granteeId;
        g.permCode = permCode;
        g.scope = scope;
        g.scopeRef = scopeRef;
        g.startAt = start;
        g.endAt = end;
        g.status = start.isAfter(now) ? "PENDING" : "ACTIVE";
        g.grantedBy = grantedBy;
        g.reason = reason;
        g.createdAt = now;
        grantMapper.insert(g);
        appendAudit(grantedBy, "grant:create", g.id, "SUCCESS", reason);
        return g;
    }

    public void withdraw(String grantId, String actorId) {
        SysGrant g = grantMapper.selectById(grantId);
        if (g == null) {
            throw new BizException(404, "授权不存在");
        }
        if ("ACTIVE".equals(g.status) || "PENDING".equals(g.status)) {
            g.status = "WITHDRAWN";
            grantMapper.updateById(g);
            employeeMapper.bumpScopeVersion(g.granteeId);
            appendAudit(actorId, "grant:withdraw", grantId, "SUCCESS", g.reason);
        }
    }

    /** 到期回收 + 待激活激活（独立 cron，沿用 DispatchService 模式；不触派单）。 */
    @Scheduled(cron = "0 */5 * * * ?", zone = "Asia/Shanghai")
    public int revokeExpired() {
        LocalDateTime now = TimeUtil.now();
        List<SysGrant> candidates = grantMapper.selectList(new LambdaQueryWrapper<SysGrant>()
                .in(SysGrant::getStatus, "ACTIVE", "PENDING"));
        int n = 0;
        for (SysGrant g : candidates) {
            if ("ACTIVE".equals(g.status) && !g.endAt.isAfter(now)) {
                g.status = "REVOKED";
                grantMapper.updateById(g);
                employeeMapper.bumpScopeVersion(g.granteeId);
                appendAudit("system", "grant:revoke", g.id, "SUCCESS", "到期自动回收");
                n++;
            } else if ("PENDING".equals(g.status) && !g.startAt.isAfter(now)) {
                g.status = "ACTIVE";
                grantMapper.updateById(g);
                appendAudit("system", "grant:activate", g.id, "SUCCESS", null);
                n++;
            }
        }
        return n;
    }

    /** 解析集成：当前 ACTIVE 授权 perm_code 并集（仅 [start,end) 内，状态已含时效）。 */
    public Set<String> activePermCodes(String granteeId) {
        Set<String> codes = new LinkedHashSet<>();
        for (SysGrant g : grantMapper.selectList(new LambdaQueryWrapper<SysGrant>()
                .eq(SysGrant::getGranteeId, granteeId)
                .eq(SysGrant::getStatus, "ACTIVE"))) {
            codes.add(g.permCode);
        }
        return codes;
    }

    private void appendAudit(String actorId, String action, String target, String result, String reason) {
        try {
            SysAuditLog a = new SysAuditLog();
            a.actorId = actorId;
            a.action = action;
            a.target = target;
            a.result = result;
            a.reason = reason;
            auditLogService.append(a);
        } catch (RuntimeException ex) {
            log.warn("授权审计落库失败（不影响主流程）: {}", ex.getMessage());
        }
    }
}
```

> `SysGrant::getStatus`/`getGranteeId` 由 Lombok @Data 生成。审计吞异常沿用 AuditInterceptor 纪律（spec §5.2）。回收只改 grant 状态 + scope_version，不触派单（spec §9 风险7）。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=GrantServiceTest`
Expected: PASS（end>start、四态、回收 bump+审计、并集全过）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/service/GrantService.java backend/src/test/java/com/cqwlw/maintenance/service/GrantServiceTest.java
git commit -m "feat: 限时授权生命周期与到期回收定时任务"
```

---

### Task 11: AuditLogService + 越权 BLOCKED 审计钩子

**Files:**
- Create: `service/AuditLogService.java`
- Modify: `common/GlobalExceptionHandler.java`（增 AuditLogService 依赖 + 403/1403 BLOCKED 钩子）
- Test: `backend/src/test/java/com/cqwlw/maintenance/service/AuditLogServiceTest.java`、`backend/src/test/java/com/cqwlw/maintenance/common/GlobalExceptionHandlerAuditTest.java`

**Interfaces:**
- Consumes: `SysAuditLogMapper`（Task 3）、`Ids.next("audit")`、`TimeUtil.now()`、`AuthInterceptor.ATTR_EMP_ID`/`ATTR_ROLE`。
- Produces:
  - `AuditLogService(SysAuditLogMapper)`（@Service 1 参）。
  - `void append(SysAuditLog entry)` — 补 id/createdAt（若空）后 insert；**无删除/更新方法**（只读不可删改）。
  - `List<SysAuditLog> query(String actorId, String action, int limit)` — 只读查询。
  - `GlobalExceptionHandler(AuditLogService)` 构造器；`handleBiz(BizException, HttpServletRequest)` 对 code 403/1403 写 BLOCKED 审计后返回原 ApiResponse。

- [ ] **Step 1: Write the failing test**

`AuditLogServiceTest.java`：

```java
package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.mapper.SysAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 审计 append-only：补 id/createdAt，无删改方法。 */
class AuditLogServiceTest {

    private final SysAuditLogMapper mapper = mock(SysAuditLogMapper.class);
    private final AuditLogService service = new AuditLogService(mapper);

    @Test
    void appendFillsIdAndCreatedAt() {
        SysAuditLog a = new SysAuditLog();
        a.action = "grant:create";
        a.result = "SUCCESS";
        service.append(a);
        ArgumentCaptor<SysAuditLog> cap = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(mapper).insert(cap.capture());
        assertNotNull(cap.getValue().id);
        assertNotNull(cap.getValue().createdAt);
    }

    @Test
    void noDeleteOrUpdateMethods() {
        for (Method m : AuditLogService.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            assertFalse(n.contains("delete") || n.contains("remove") || n.contains("update"),
                    "审计只读不可删改，发现写方法: " + m.getName());
        }
        assertTrue(java.util.Arrays.stream(AuditLogService.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("query")));
    }
}
```

`GlobalExceptionHandlerAuditTest.java`：

```java
package com.cqwlw.maintenance.common;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 越权拒绝路径写 BLOCKED 审计；非越权业务码不写。 */
class GlobalExceptionHandlerAuditTest {

    private final AuditLogService audit = mock(AuditLogService.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(audit);

    private HttpServletRequest request() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRequestURI()).thenReturn("/admin/employees");
        when(req.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn("emp_1");
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("WORKER");
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        return req;
    }

    @Test
    void forbiddenWritesBlockedAudit() {
        ApiResponse<Object> resp = handler.handleBiz(new BizException(403, "无权限"), request());
        assertEquals(403, resp.getCode());
        ArgumentCaptor<SysAuditLog> cap = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(audit).append(cap.capture());
        assertEquals("BLOCKED", cap.getValue().result);
        assertEquals("emp_1", cap.getValue().actorId);
    }

    @Test
    void dataScopeDeniedWritesBlockedAudit() {
        handler.handleBiz(new BizException(1403, "越权"), request());
        verify(audit).append(org.mockito.ArgumentMatchers.any(SysAuditLog.class));
    }

    @Test
    void validationErrorDoesNotWriteAudit() {
        handler.handleBiz(new BizException(422, "参数错误"), request());
        verify(audit, never()).append(org.mockito.ArgumentMatchers.any(SysAuditLog.class));
    }
}
```

> `BizException(int code, String message)`/`getCode()→int` 与 `ApiResponse.error(int,String)`/`getCode()→int` 已核对源码确认；`GlobalExceptionHandler` 现有 `handleBiz(BizException)` 1 参（本任务加 `HttpServletRequest` 成 2 参）与 `private static final Logger log` 均已存在，BLOCKED 钩子的 `log.warn` 可直接编译。

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=AuditLogServiceTest,GlobalExceptionHandlerAuditTest`
Expected: FAIL — 编译错误（AuditLogService 不存在；GlobalExceptionHandler 无 1 参构造器/handleBiz 无 request 参）。

- [ ] **Step 3: Implement AuditLogService and the BLOCKED hook**

`service/AuditLogService.java`：

```java
package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.mapper.SysAuditLogMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 业务审计（spec §5.2）：只 append + 查询，无删除/更新端点（只读不可删改）。
 * 与旧 op_log + AuditInterceptor 并存；落库失败由调用方吞异常（不影响主流程）。
 */
@Service
public class AuditLogService {

    private final SysAuditLogMapper mapper;

    public AuditLogService(SysAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    public void append(SysAuditLog entry) {
        if (entry.id == null || entry.id.isBlank()) {
            entry.id = Ids.next("audit");
        }
        if (entry.createdAt == null) {
            entry.createdAt = TimeUtil.now();
        }
        mapper.insert(entry);
    }

    public List<SysAuditLog> query(String actorId, String action, int limit) {
        LambdaQueryWrapper<SysAuditLog> w = new LambdaQueryWrapper<SysAuditLog>()
                .orderByDesc(SysAuditLog::getCreatedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 500)));
        if (actorId != null && !actorId.isBlank()) {
            w.eq(SysAuditLog::getActorId, actorId);
        }
        if (action != null && !action.isBlank()) {
            w.eq(SysAuditLog::getAction, action);
        }
        return mapper.selectList(w);
    }
}
```

`common/GlobalExceptionHandler.java`：增构造器依赖 + handleBiz 增 request 参 + BLOCKED 钩子（其余 handler 不动）：

```java
    private final com.cqwlw.maintenance.service.AuditLogService auditLogService;

    public GlobalExceptionHandler(com.cqwlw.maintenance.service.AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Object> handleBiz(BizException e, jakarta.servlet.http.HttpServletRequest request) {
        if (e.getCode() == 403 || e.getCode() == 1403) {
            appendBlocked(e, request);
        }
        ApiResponse<Object> resp = ApiResponse.error(e.getCode(), e.getMessage());
        if (e.getData() != null) {
            resp.setData(e.getData());
        }
        return resp;
    }

    /** 越权拒绝路径 BLOCKED 审计（spec §5.2）；落库失败不影响错误响应。 */
    private void appendBlocked(BizException e, jakarta.servlet.http.HttpServletRequest request) {
        try {
            com.cqwlw.maintenance.entity.SysAuditLog a = new com.cqwlw.maintenance.entity.SysAuditLog();
            a.actorId = (String) request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID);
            a.actorRole = (String) request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_ROLE);
            a.action = request.getMethod() + " " + request.getRequestURI();
            a.result = "BLOCKED";
            a.reason = e.getMessage();
            a.ip = request.getRemoteAddr();
            auditLogService.append(a);
        } catch (RuntimeException ex) {
            log.warn("BLOCKED 审计落库失败（不影响主流程）: {}", ex.getMessage());
        }
    }
```

> 日志脱敏：reason 取 BizException message（业务文案，不含 token/手机号/openid/密钥）；ip 为请求地址（非敏感凭证）。action 截断到 ≤64 字符以符合 DDL（实现时 `a.action = (method + " " + uri)` 若超 64 取前 64）。GlobalExceptionHandler 无现有测试，增构造器依赖由 Spring 注入 AuditLogService，安全。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=AuditLogServiceTest,GlobalExceptionHandlerAuditTest,AuditInterceptorTest`
Expected: PASS（append-only、BLOCKED 钩子、422 不写；AuditInterceptorTest 1 参字面不动仍绿，op_log 不受影响）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/service/AuditLogService.java backend/src/main/java/com/cqwlw/maintenance/common/GlobalExceptionHandler.java backend/src/test/java/com/cqwlw/maintenance/service/AuditLogServiceTest.java backend/src/test/java/com/cqwlw/maintenance/common/GlobalExceptionHandlerAuditTest.java
git commit -m "feat: 业务审计底座与越权 BLOCKED 留痕"
```

---

### Task 12: DemoDataSeeder 张工 INST_MGR + 组织/角色演示种子

**Files:**
- Modify: `service/DemoDataSeeder.java`（增 SysOrgMapper/SysUserRoleMapper + 张工 + org/user_role 种子）
- Modify: `backend/src/test/java/com/cqwlw/maintenance/DemoDataSeederTest.java`（构造器 +2 mock + 张工断言）

**Interfaces:**
- Consumes: `SysOrgMapper`/`SysUserRoleMapper`/`SysOrg`/`SysUserRole`（Task 3）、`Ids.next`、`TimeUtil.now`、`seed()` 内现有局部 `BCryptPasswordEncoder enc`、`AppProperties.isSeedDemoData()`。张工**手动构建 Employee**（不用 `employee(...)` helper——其 11 参签名内部即 insert，无法在 insert 前设 department）。
- Produces: 张工账号（sys_employee.role="INST_MGR"，department="INSTALL"）+ sys_user_role(INST_MGR, org=安装部节点) + 演示 org 节点。守卫 SEED_DEMO_DATA + 空库（现状 run() 已有），**绝不进 Flyway/生产**。

- [ ] **Step 1: Write the failing test**

在 `DemoDataSeederTest.java`：构造器增 2 mock（17 参），并追加张工断言。`setUp` 改为：

```java
        seeder = new DemoDataSeeder(props, mock(CompanyMapper.class), employeeMapper,
                mock(UseUnitMapper.class), elevatorMapper, orderMapper, recordMapper,
                mock(MessageMapper.class), mock(RescueMapper.class), mock(FaultMapper.class),
                mock(DrillMapper.class), mock(InspectRecordMapper.class), mock(KnowledgeMapper.class),
                checklistService, mock(WorkOrderService.class),
                mock(com.cqwlw.maintenance.mapper.SysOrgMapper.class),
                mock(com.cqwlw.maintenance.mapper.SysUserRoleMapper.class));
```

追加测试方法：

```java
    @Test
    void seedsZhangGongAsInstMgrWithUserRole() throws Exception {
        seeder.run(null);
        ArgumentCaptor<Employee> employees = ArgumentCaptor.forClass(Employee.class);
        verify(employeeMapper, atLeastOnce()).insert(employees.capture());
        Employee zhang = employees.getAllValues().stream()
                .filter(e -> "INST_MGR".equals(e.role)).findFirst().orElse(null);
        assertTrue(zhang != null, "种子应含张工 INST_MGR 测试账号（P-1）");
        assertEquals("INSTALL", zhang.department);
        // 不升任张伟 emp_1（张伟仍 WORKER）
        Employee zhangWei = employees.getAllValues().stream()
                .filter(e -> "emp_1".equals(e.id)).findFirst().orElse(null);
        assertTrue(zhangWei != null && "WORKER".equals(zhangWei.role), "张伟 emp_1 不得升任");
    }
```

> 现有两个测试（platformId 一致性、班组长覆盖）保持不动且必须仍绿：张工属 INSTALL 部、不在维保一班，不影响班组覆盖断言；张工若设 platformId 则属种子员工集合，不破坏 platformId 一致性。

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && mvn -q test -Dtest=DemoDataSeederTest`
Expected: FAIL — 编译错误（DemoDataSeeder 现 15 参构造器，无 17 参）。

- [ ] **Step 3: Implement seeder additions**

`service/DemoDataSeeder.java`：构造器增 `SysOrgMapper orgMapper, SysUserRoleMapper userRoleMapper` 两参（追加在 WorkOrderService 之后），存为字段。在 `seed()` 末尾（现有 unitRecord 种子之后）追加：

```java
        // 张工 INST_MGR 测试账号（P-1，spec §6.1；不升任张伟 emp_1）。仅演示种子，绝不进 Flyway/生产。
        // 不用 employee() helper（其 11 参签名内部即 insert，无法在 insert 前设 department）——
        // 手动构建以便 department 先于 insert 落值，且只 insert 一次（避免重复插入）。
        Employee zhang = new Employee();
        zhang.id = "emp_zhang";
        zhang.name = "张工";
        zhang.phone = "13800000099";
        zhang.account = "13800000099";
        zhang.passwordHash = enc.encode("123456");
        zhang.role = "INST_MGR";
        zhang.roleText = "安装部主管";
        zhang.department = "INSTALL";
        zhang.groupName = "";
        zhang.platformId = "P_ZHANG";
        zhang.workStat = "normal";
        zhang.syncStatus = "ACTIVE";
        employeeMapper.insert(zhang);

        // 演示组织节点：安装部（V36 已在空库建部门骨架，故先查后插避免主键冲突）+ 张工角色锚点。
        if (orgMapper.selectById("org_dept_install") == null) {
            SysOrg installDept = new SysOrg();
            installDept.id = "org_dept_install";
            installDept.type = "DEPT";
            installDept.name = "安装部";
            installDept.code = "DEPT_INSTALL";
            installDept.status = "ACTIVE";
            installDept.sortOrder = 1;
            installDept.createdAt = TimeUtil.now();
            orgMapper.insert(installDept);
        }

        SysUserRole zhangRole = new SysUserRole();
        zhangRole.id = Ids.next("ur");
        zhangRole.userId = "emp_zhang";
        zhangRole.userType = "EMPLOYEE";
        zhangRole.roleCode = "INST_MGR";
        zhangRole.orgId = "org_dept_install";
        zhangRole.createdAt = TimeUtil.now();
        userRoleMapper.insert(zhangRole);
```

> import 增 `SysOrg`/`SysUserRole`/`SysOrgMapper`/`SysUserRoleMapper`/`Ids`（`TimeUtil`/`Employee`/`BCryptPasswordEncoder` 已引入）。`enc` 是 `seed()` 内现有局部变量。张工 platformId="P_ZHANG" 属种子员工集合，不破坏 DemoDataSeederTest 的 platformId 一致性断言；张工不在维保一班、非工单作业人员，不破坏班组长覆盖断言。org 节点先查后插：运行时 Flyway V36 已建 `org_dept_install`，Seeder 跳过；单测中 orgMapper 为 mock，selectById 返 null → 走 insert（无断言依赖）。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && mvn -q test -Dtest=DemoDataSeederTest`
Expected: PASS（张工 INST_MGR + sys_user_role；现有 platformId/班组长两测试仍绿）。

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/cqwlw/maintenance/service/DemoDataSeeder.java backend/src/test/java/com/cqwlw/maintenance/DemoDataSeederTest.java
git commit -m "feat: 演示种子增张工 INST_MGR 与组织角色锚点"
```

---

### Task 13: 全量门禁 + 文档同步

**Files:**
- Modify: `AGENTS.md`（§1 基线计数 + §10 修订记录）、`docs/12-未完成事项清单.md`（M1 状态）
- 无代码改动（验证 + 文档任务）

**Interfaces:**
- Consumes: Task 1–12 全部产物。
- Produces: 全绿门禁证据 + 文档同步。M1 不新增 REST 端点（grant 管理 M3、audit 视图 M5），故 docs/04 三处同步**不触发**；仅同步计数与待办状态。

- [ ] **Step 1: 后端全量测试**

Run: `cd backend && mvn clean test`
Expected: PASS — 322 基线不回退 + 新增（RbacV2MigrationTest/RbacEntityMappingTest/RoleCatalogV2Test/PermissionMatrixV2Test/LegacyCapabilityTranslatorTest/JwtServiceSvTest/AuthInterceptorV2Test/ScopeResolverEquivalenceTest/GrantServiceTest/AuditLogServiceTest/GlobalExceptionHandlerAuditTest）。记录实际测试总数。

- [ ] **Step 2: 打包完整性**

Run: `cd backend && mvn clean package -DskipTests`
Expected: BUILD SUCCESS（MigrationIntegrityTest 已在 Step 1 守同版本重复；clean 避免 target/classes 残留，AGENTS §3.2）。

- [ ] **Step 3: 派单/模板回归脚本**

Run: `node scripts/verify-dispatch.js && node scripts/verify-templates.js`
Expected: PASS（鉴权改动未触派单/模板链路；spec §7.3 必跑）。

- [ ] **Step 4: 管理端三件套（零回归确认）**

Run: `cd admin && npm run lint && npm test && npm run build`
Expected: PASS（M1 不改 admin 代码，旧端经兼容层照常可用）。Windows esbuild 报 `Access is denied` 时把 `TMP/TEMP` 指向 `admin/.tmp-build`（AGENTS §4.4 / docs/09 V3.5）。

- [ ] **Step 5: 文档同步**

- `AGENTS.md` §1 基线：Flyway V1—V36、后端单测数更新为 Step 1 实测值；§10 追加一行修订记录（V3.11，2026-11-14，M1 权限底座：13 规范角色/86 三态码/翻译兼容层/多角色会话/scopeVersion/ScopeResolver 等价/限时授权/业务审计/V35—V36；旧端点行为逐条不变，业务查询/admin/小程序零改动）。**不修改文末 AOCI 托管块。**
- `docs/12-未完成事项清单.md`：M1 后端权限底座标记完成；保留 M2—M5 待办；生产清库（一次性人工上线步骤）仍列待办。
- 核对 spec §8 偏离精化 M1-1..M1-6 均已落地（五表/只建证等价/scope_ref 精化/console 门控/不改旧码值/V35V36 拆分+张工归 Seeder）。

- [ ] **Step 6: Commit**

```bash
git add AGENTS.md docs/12-未完成事项清单.md
git commit -m "docs: 同步 M1 权限底座基线计数与待办状态"
```

---

## Self-Review

**1. Spec coverage（逐节核对）：**
- §2.1 五表 DDL + scope_version → Task 1 ✓；§2.2 角色范围种子 → Task 1 ✓；§2.3 scope_ref 精化（sys_role_scope 存枚举+version，逐用户锚点 sys_user_role.org_id）→ Task 1/2/3 ✓。
- §3.1 RoleCatalog v2（13 角色+别名+同部门兼任）→ Task 4 ✓；§3.2 矩阵 v2 三态+24 旧常量保留+fail-closed → Task 5 ✓；§3.3 翻译器（四关键码+adminConsole 门控+order/fault 反转+20 码映射）→ Task 6 ✓；§3.4 CompatTest 字面不动 → Task 6 Step 4 验证 ✓。
- §4.2 AuthInterceptor v2（sv 校验+旧 token 兼容+ATTR_ROLES+门户别名）→ Task 7/8 ✓；§4.3 AdminRoleInterceptor 字面不动 → 锁定事实表 + Task 6 验证 ✓；§4.4 ScopeResolver 读写双谓词 → Task 9 ✓；§4.5 等价证明 → Task 9 ✓；§4.6 旧码值不改 → Global Constraints + Task 2 注释 ✓。
- §5.1 限时授权（四态+回收+bumpScopeVersion+审计+解析并集）→ Task 10 ✓；§5.2 审计（append-only+BLOCKED+op_log 并存+脱敏）→ Task 11 ✓。
- §6.1 V35/V36 拆分+张工归 Seeder → Task 1/2/12 ✓；§6.3 严禁自动 TRUNCATE/DELETE → Task 1/2 测试断言 ✓。
- §7 测试门禁（322 不回退+verify 脚本+admin 三件套）→ Task 13 ✓。
- §8 M1-1..M1-6 偏离精化 → Task 13 Step 5 核对 ✓。

**2. Placeholder scan：** 无 TBD/TODO/"similar to Task N"。每个代码步含完整代码块。原「实现前核对」提示已逐条对源码核验并落定为确认陈述：①Task 2 表名 `company`/`use_unit`/`sys_employee` 与列名（`unit_name`/`group_name`）已核对实体 `@TableName`/字段；②Task 7 `AppProperties.setJwtSecret/getJwtSecret/getJwtExpireHours`（默认 72）已确认；③Task 11 `BizException(int,String)`/`ApiResponse.error(int,String)`/`GlobalExceptionHandler` 现有 `log` 字段已确认；④Task 12 `employee(...)` 11 参 helper 内部即 insert，张工改手动构建（已写入 Consumes 与 Step 3 注释）。矩阵 86 码数据逐字来自 roles-permissions.md §4 + M0 §2.2，已全量列出；24→71 翻译 MAP 全量列出。

**3. Type consistency：**
- `JwtService.issue` **6 参 + 保留 5 参重载**（Task 7）：6 参与 AuthController 调用、UnifiedLoginTest mock（`anyInt()`）一致；5 参重载委托 sv=0 使 `JwtServiceTest.java:23` 字面不动仍绿。构建用 `Map<String,Object> claims` + `.claims(claims)`（非链式 `.claim()`），与现状一致。
- `PermissionMatrix.holders(String)→Set<String>`（Task 5 新增 public 访问器，因 `grant(...)` 是 private void builder）：翻译器 `holders(oldCode).contains(role)`、矩阵测试 `holders("console:read")`/`holders(cap)` 一致；`allows` 改为 `requireKnownCapability(cap)` + 委托 `LegacyCapabilityTranslator.allows(role,cap)`（单一版本，无二义）。
- `AuthInterceptor` 3 参 @Autowired + 2 参委托（Task 8）与 AuthInterceptorV2Test/UnitTokenScopeTest/AuthInterceptorTest 一致；ATTR_ROLES="roles" 常量贯穿 Task 8/11。
- `EmployeeMapper.bumpScopeVersion(String)`（Task 3）与 GrantService 调用（Task 10）一致；`Employee.scopeVersion`（Integer，null 安全）贯穿 Task 3/7/8。
- `AuditLogService.append(SysAuditLog)`（Task 11）与 GrantService（Task 10）、GlobalExceptionHandler（Task 11，`handleBiz` 加 `HttpServletRequest` 成 2 参）一致。
- `ScopeResolver(EmployeeScopeService, DeptScopeService, SysRoleScopeMapper)`（Task 9）与等价测试构造一致；旧角色 read/write 委托 V7（`EmployeeScopeService` 1 参 / `DeptScopeService` 1+2 参构造均未改）。
- `DemoDataSeeder` **17 参**（Task 12，追加 SysOrgMapper/SysUserRoleMapper 于 WorkOrderService 后）与 DemoDataSeederTest 构造调用一致；张工手动构建（department 先于单次 insert），org 节点 `selectById("org_dept_install")==null` 守卫避免与 V36 部门骨架主键冲突。
- `PermissionMatrix.state/allowsNew/canonicalCapabilities/TriState`（Task 5）与翻译器（Task 6）、矩阵测试一致；`RoleCatalog.canonical/canonicalRole/canonicalCodes/sameDepartmentAssignable`（Task 4）与 Task 5/6/8/9 一致；`codes()` 仍返回 12 旧码（CompatTest 锁定）。
- 新码常量 `REGION_WRITE_NEW`/`TEMPLATE_WRITE_NEW` 避免与旧常量重名，字符串值与旧码相同（Task 5 注）。
- **角色码区分（已核验非缺陷）**：`INST_MGR`（安装部主管，ORG，adminConsole=true，parentRole=null，张工新账号）与 `INST_PM`（安装部项目负责人，PROJECT，old `INSTALL_MANAGER` 别名）是两个不同规范角色；V36 仅 `INSTALL_MANAGER→INST_PM`，且断言 `!contains("INST_MGR")`（张工归 Seeder）；ALIAS 不含 INST_MGR，`canonical("INST_MGR")` 为恒等。
- **V36 主键长度（已核验）**：所有派生 id = `前缀(3)+SUBSTRING(MD5(...),1,28)=31≤VARCHAR(32)`；`sys_org.id` 与 `sys_user_role.org_id` 锚定用同一表达式（TEAM=`ot_`、UNIT=`ou_`）；`code` 列 VARCHAR(64) 容 `TEAM_`+32 位 MD5=37；DEPT 骨架用字面 id（`org_dept_install` 等，≤32）。测试断言 `INSERT IGNORE INTO`（与 SQL 一致）。

**已知边界（忠实 spec，非缺陷）：** 新三态矩阵按目标态编码（record:view 不含 CA），旧别名 allows 走冻结 GRANTS（record:read 含 UA）——M1 保留旧行为，目标态差异推迟 M5。canUseAdminConsole(CLIENT_ADMIN)=false（M5 才开 Web）。C 态审批工作流不实现（M5）。