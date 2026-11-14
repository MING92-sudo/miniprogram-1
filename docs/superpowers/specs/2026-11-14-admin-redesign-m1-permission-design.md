# M1 后端权限底座设计（梯维通重构 · 第一个实施子项目）

**日期**：2026-11-14
**状态**：设计已在对话中逐节确认（§1—§6），待用户评审本文件
**范围**：M1 后端权限底座——角色目录 v2 / 权限矩阵 v2（三态）/ 旧码翻译兼容层 / 多角色会话 / ScopeResolver 读写双谓词 / 限时授权到期回收 / 业务审计底座 / Flyway V35—V36。**仅动 backend 鉴权层**，业务查询、admin、小程序代码零改动。
**上级依据**：程序级 spec `2026-11-14-admin-redesign-design.md` V1.7（§4 目标模型 / §5 数据模型 / §9 M1 门禁）+ M0 对照表 `2026-11-14-admin-redesign-m0-mappings.md` V1.5（表 1 角色映射 / 表 2 权限码映射）
**外部基准**：`docs/16-梯维通设计输入/roles-permissions.md` V1.3（71 码 / 6 范围 / §13.4 审计 / §10 限时授权）

---

## 1. 意图与边界

M1 把现有「12 角色单列 + 24 粗粒度二值码 + console:read/write 两档门禁」升级为「13 角色多行 + 71+ 三态码 + 6 范围读写双谓词」的目标底座，同时**旧端点行为逐条不变**（兼容期）。M1 是底座，不接业务视图。

### 1.1 两项边界裁决（2026-11-14 对话）

- **Q1 建表范围 = 权限核心五表**：M1 只建 `sys_org` / `sys_user_role` / `sys_role_scope` / `sys_grant` / `sys_audit_log`。程序 spec §5.1 的 `sys_invite` / `sys_config` / `biz_export_approval` / `biz_elevator_change` 与 §5.2 域加列（记录版本链 / 代报修 / SLA 快照）**推迟到 M3/M4/M5** 各自落地。程序 spec §9 M1 行「九表+加列」据此收窄为「权限核心五表」（见 §8 M1-1）。
- **Q2 解析器接入 = 只建+证等价**：M1 交付 ScopeResolver 与读写双谓词，并用测试证明对旧角色产出与现有 V7 谓词等价；**现有电梯/工单/记录列表查询保持原谓词不动**，逐视图接入留 M3/M4。M1 回归面只在鉴权层。

### 1.2 架构方案 = 方案 1（单一新矩阵 + 旧码翻译适配）

`PermissionMatrix` 升级为 71+ 三态码的**唯一事实源**，24 旧码保留为兼容别名，M0 §2.1 映射表做成一等公民翻译器；粗门禁结构不变、能力判定走翻译；`ATTR_ROLE` 保留单角色供旧门禁、新增 `ATTR_ROLES` 多角色集供新细粒度。不维护第二份权限事实源（契合程序 spec §2 反目标）。

### 1.3 不做（M1 反目标）

- 不接业务视图、不改业务查询（Q2）；不改 admin / 小程序代码。
- 不实现 C 态「需审批」工作流（依赖 `biz_export_approval`，M5）；M1 只编码三态状态。
- 不改 `sys_employee.role` 旧码值（推迟 M5，见 §8 M1-5）。
- 不实现邀请码 / 系统配置 / 敏感导出审批 / 电梯双人确认（域表 M3/M4/M5）。
- 不做任何自动 TRUNCATE/DELETE 数据迁移（生产清库是人工上线步骤，AGENTS §6）。
- 不触派单不变量（AGENTS §3.6）、不引入上报自动重试（AGENTS §2.4）。

---

## 2. 数据模型（权限核心五表 + 一列）

**建表约定跟随仓库既有模式**（见 `region`/`op_log`/`sys_employee`）：`VARCHAR(32)` 字符串主键 + `VARCHAR(16)` 字符串枚举 + `DATETIME` + `KEY idx_` + `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` + 列 COMMENT。**不套用**通用模板的 bigint/tinyint/deleted/create_by（仓库无此约定）。主键经 `Ids.next(prefix)` 生成，时间经 `TimeUtil.now()`。

### 2.1 V35 DDL（schema + 静态配置）

```sql
-- V35__rbac_v2_schema.sql
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
```

### 2.2 sys_role_scope 静态配置种子（V35，全环境）

| role_code | scope | 说明 |
|---|---|---|
| PLATFORM_ADMIN | ALL | 平台全量（运维只读语义由动作码收敛） |
| SYS_ADMIN | ALL | 保留不启用，同 PLATFORM_ADMIN |
| INST_MGR | ORG | 安装部及下级 |
| INST_PM | PROJECT | 被指派安装项目集 |
| INST_CLERK | PROJECT | 被指派安装项目（仅资料/整改证据） |
| INSTALLER | SELF | 本人被指派查验/整改 |
| MAINT_MGR | ALL | 全部维保项目（resolver 加 department=MAINT 过滤） |
| MAINT_QUALITY | ORG | 维保部跨班组只读 + 抽检写 |
| MAINT_BIZ | ORG | 维保部合同/客户域（不见现场作业数据 R4） |
| MAINT_SUP | TEAM | 现有班组（P-2/D-A9，不新增项目部层） |
| MAINT_TECH | SELF | 本人派工；读谓词另加区域扩展（D-A7） |
| CLIENT_ADMIN | ORG_FLAT | 仅本单位 |
| CLIENT_SIGNER | ORG_FLAT | 仅本单位 |

### 2.3 scope_ref 精化（§1 对话确认，见 §8 M1-3）

程序 spec §5.1 把 `scope_ref` 写在 `sys_role_scope`，但「哪个班组/项目」是**逐用户**的。本设计：`sys_role_scope` 只存角色**默认范围枚举** + version（scope_ref 多数为空，仅固定范围角色用）；**逐用户组织锚点放 `sys_user_role.org_id`**，解析时结合现有 `sys_employee.group_name/region_id/manager_id`。M1 不动现有班组谓词即可证等价。

---

## 3. 角色目录 v2 + 权限矩阵 v2 + 翻译器

### 3.1 RoleCatalog v2（13 规范角色 + 旧码别名）

`record Role(code, department, name, adminConsole, parentRole)` 结构沿用。新增别名表（旧→规范）：

| 旧码 | 规范码 | 部门 | adminConsole |
|---|---|---|---|
| ADMIN | PLATFORM_ADMIN | PLATFORM | 是 |
| SYS_ADMIN | SYS_ADMIN | PLATFORM | 是 |
| INSTALL_MANAGER | INST_PM | INSTALL | 是 |
| INSTALL_CLERK | INST_CLERK | INSTALL | 是 |
| INSTALL_WORKER | INSTALLER | INSTALL | 否 |
| —（新增） | INST_MGR | INSTALL | 是 |
| MAINT_MANAGER | MAINT_MGR | MAINT | 是 |
| LEADER | MAINT_SUP | MAINT | 是 |
| WORKER / ASSISTANT | MAINT_TECH | MAINT | 否 |
| MAINT_BIZ | MAINT_BIZ | MAINT | 是 |
| MAINT_QUALITY | MAINT_QUALITY | MAINT | 是 |
| UNIT_ADMIN | CLIENT_ADMIN | UNIT | 是（M5 新能力） |
| —（新增） | CLIENT_SIGNER | UNIT | 否 |

- `role(旧码)` 返回规范角色；旧 token / 旧注解零感知。`departmentOf`/`nameOf`/`canUseAdminConsole`/`known` 对旧新码均生效。
- 多角色兼任：**同部门内**允许、跨部门拒绝（docs/14 R7，偏离 D-A2）；`assignableRoles` 排除 UNIT 域员工目录。
- Web 可登录 10 角色（PA/SA/MM/MS/IM/IP/IC/MB/MQ/CA）；小程序-only 3 角色（MT/IW/CS）。

### 3.2 PermissionMatrix v2（三态，唯一事实源）

- 结构由 `capability→Set<role>`（二值）改为 `role×code→TriState{Y,C,-}`；71+ 码（6 域 + M0 §2.2 扩展码）。
- **24 旧码常量保留**为兼容别名（`AdminRoleInterceptorCompatTest` 引用 `PermissionMatrix.REGION_WRITE` 须仍编译）。
- fail-closed 初始化沿用现有纪律：未知角色/码 → `IllegalStateException` 启动失败；矩阵码集与常量集一致性校验。
- 新 API：`state(role,code)→TriState`；`allowsNew(role,code) = state==Y`。旧 `allows(role,旧码)` 保留为二进制兼容入口，内部委托翻译器。
- **C 态语义**：M1 只编码状态（只读/需审批徽标）；「需审批」工作流依赖 `biz_export_approval`（M5），本期不实现。`@RequirePerm(新码)` 默认要求 Y（细粒度端点 M3+ 才标注，现零使用）。

### 3.3 LegacyCapabilityTranslator（24→71，M0 §2.1 为唯一映射源）

- 兼容关键四码（拦截器实际用到）：`console:read`、`console:write→config:manage`、`plan:write→plan:generate`（手动语义 D-A1）、`region:write`。
- **adminConsole 门控（必须守住，见 §8 M1-4）**：`console:read(role) = adminConsole(role) ∧ 任一域 view∈{Y,C}`。MT/IW/CS 虽有小程序 view 码，但 adminConsole=false → console:read=false，保住「worker 登录管理端即 403」。`CompatTest` 的 WORKER×`/admin/dashboard` 期望 403 是守门测试。
- **order/fault 语义反转（M0 §2.0，Blocker 级陷阱）**：旧 `order:*`=维保工单 → 新 `plan/task/checklist/record`；旧 `fault:*`=急修单 → 新 `order:*`。禁止字面同名映射。
- 其余 20 码按 M0 §2.1 逐码映射（供 MenuService 与未来端点）。

### 3.4 兼容保证

`AdminRoleInterceptorCompatTest` 字面不动且保持绿（5 角色×17 路由）；`MenuService` 本期仍走旧码（经翻译器）→ 旧菜单不变，新 IA 菜单 M3+ 再接。

---

## 4. 鉴权链 v2 + ScopeResolver 等价证明

### 4.1 鉴权链

```
AuthInterceptor（JWT → empId/ATTR_ROLE 主角色/ATTR_ROLES 规范码集/useUnitId/scopeVersion）
  → RoleCatalog v2（角色 → 部门 + 层级 + 可用端，旧码别名翻译）
  → PermissionMatrix v2（角色 × 71+ 码 → Y/C/-，403）
  → ScopeResolver（6 范围 → 读/写双谓词，越权 1403）
  → 业务归属校验（单据在范围内，否则 403/1403 且不泄露存在性）
```

### 4.2 AuthInterceptor v2

- 员工令牌：sid 会话校验后增 **scopeVersion 校验**——JWT 新增 `sv` claim，登录取 `sys_employee.scope_version`；请求时 `sv != user.scope_version → 401`。
- **旧 token 兼容**：pre-M1 token 无 `sv` claim → 取 `sv=null→0`，与默认 `scope_version=0` 匹配，旧 token 不误拒。
- 多角色：读 `sys_user_role` → `ATTR_ROLES`（规范码集）；`ATTR_ROLE` 保留为主角色单值（单角色账号 = `sys_employee.role` 旧码，逐字不变）。
- 门户令牌：`UNIT_ADMIN` 硬编码改认 `CLIENT_ADMIN`（+ 旧 `UNIT_ADMIN` 别名兼容旧 token）；`/unit/`+`/files/` 路径限制不变；GET `/files/*` 签名把门 bypass 不变。

### 4.3 AdminRoleInterceptor v2

结构字面不动（读=GET/写=非GET + `LEADER_WRITABLE`/`WORKER_WRITABLE_ELEVATOR` 两条正则例外 + GET `/elevators*` 放行）；能力判定改走翻译器；`@RequirePerm(新码)` → `allowsNew`（要求 Y）。注册前缀不变（`/admin/**`、`/reg/**`、`/platform/**`、`/company`、`/use-units/**`、`/employees/**`、`/elevators/**`；handover/filing 仍在门禁外）。

### 4.4 ScopeResolver（6 范围 → 读/写双谓词）

谓词形态沿用 V7：作业人员 `platformId` 集合，`null`=不限、`empty`=无。逐角色复现 `EmployeeScopeService`：

| 角色（规范） | 读谓词 | 写谓词 |
|---|---|---|
| PLATFORM_ADMIN / SYS_ADMIN | null（ALL） | null |
| MAINT_MGR / MAINT_QUALITY | null（ORG 部门全量） | 本人 |
| MAINT_SUP | 区域 ∪ 本组 ∪ 本人 | 本组（TEAM） |
| MAINT_TECH | 区域 ∪ 本人 | 本人（SELF） |
| INST_PM / INST_CLERK / INSTALLER / MAINT_BIZ | empty | 本人 |
| CLIENT_ADMIN / CLIENT_SIGNER | 本单位（ORG_FLAT，门户谓词） | 本单位 |

- **读写分离红线**：范围扩大（区域、部门全量）只进读谓词；写谓词严格本人/本组/被指派，防代签到代签退（AGENTS §3.1）。V7 的读写不对称（NO_FIELD_ROLES 读 empty/写本人、MM/MQ 读 null/写本人）原样保留。
- 区域读扩展（D-A7）作为附加读谓词保留；存量 region_id 为空 = 未分区，行为不变。**区域仍是独立 `region` 表**（读谓词用 `region_id`），不进 `sys_org` 树。
- 上表为 V7 复现靶（旧角色等价）。**新增角色 INST_MGR（ORG）/ CLIENT_SIGNER（ORG_FLAT）无 V7 对应**，按 `sys_role_scope` 默认范围解析，不在等价证明靶内。

### 4.5 等价证明（M1 门禁核心）

新增测试断言：对全部**员工域旧角色**（PA/SA/MM/MQ/MS/MT/INST_PM/INST_CLERK/INSTALLER/MB）× 代表性班组/区域配置，`ScopeResolver(user,role,read)` 与 `EmployeeScopeService.visibleReadPlatformIds` **逐集合相等**、`ScopeResolver(user,role,write)` 与 `visibleWorkerPlatformIds` 逐集合相等。`EmployeeScopeService` 本期**完全不动**（旧码值不改，§4.6）。**门户域 CLIENT_ADMIN/CLIENT_SIGNER**（use_unit 账号，非 sys_employee）的等价靶是 `DeptScopeService.useUnitScope`/use_unit 绑定谓词，单独断言本单位收敛。越权用例逐条留证（docs/14 §12）。

### 4.6 DB 旧码值不改（§3 对话确认，见 §8 M1-5）

`EmployeeScopeService`/`DeptScopeService` 硬编码旧角色字面量（"ADMIN"/"LEADER" 等）。为忠实 Q2「不改现有业务查询」，M1 **不改 `sys_employee.role` 旧码值**——旧列保留旧码（硬编码谓词零改动），新码只落 `sys_user_role`；RoleCatalog 别名在鉴权边界翻译；**DB 码值改名推迟 M5 兼容期结束**（届时字面量已全部迁到 RoleCatalog 常量）。

---

## 5. 限时授权 + 审计

### 5.1 限时授权 sys_grant（程序 spec §4.4 / roles §10）

- 生命周期：`PENDING`(start>now) → `ACTIVE`(start≤now<end) → `REVOKED`(到期自动) / `WITHDRAWN`(主管手动撤回)；应用层校验 `end>start`。
- **到期回收定时任务**：沿用 `DispatchService` 的 `@Scheduled(cron, zone="Asia/Shanghai")` 模式（`@EnableScheduling` 已开）。扫描 `ACTIVE ∧ end_at≤now` → 置 `REVOKED` + **递增被授权人 `scope_version`**（旧 token 立即失效、重解析时追加权限脱落）+ 写审计；同任务把 `PENDING ∧ start≤now` 激活为 `ACTIVE`。
- **解析集成**：权限/范围解析在角色默认矩阵外**并集当前 ACTIVE 授权**的 perm_code + scope（仅 [start,end) 内）；授权同样守读写分离——扩范围只进读谓词，写仍严格本人/本组/被指派，不永久扩大默认范围。
- 红线澄清：到期回收是授权时效，与 AGENTS §2.4「上报无自动重试」无关。
- M1 边界：交付机制（表 + 解析并集 + 回收任务 + 审计），测试夹具验证；发起/撤回管理端点随 M3 `#/grants`。

### 5.2 审计 sys_audit_log（roles §13.4）

- 字段见 §2.1 DDL；`result∈{SUCCESS,FAILED,BLOCKED}`。
- **只读不可删改**：`AuditLogService` 只 append + 查询，**无删除/更新端点**。
- 与 op_log 并存：旧 `op_log` + `AuditInterceptor`（HTTP 级留痕，≥3 年）原样保留；`sys_audit_log` 是业务级（权限码 action + before/after 快照）。
- M1 写入点：授权生命周期（发起/激活/回收/撤回）、角色/范围变更（递增 scope_version 时）、越权拦截 BLOCKED（鉴权拒绝路径）。六类动作全量审计视图随 M5 `#/audit`。
- 落库失败不影响主流程（沿用 AuditInterceptor 吞异常纪律）；日志脱敏（不打印完整 token/手机号/openid/平台密钥，AGENTS §2.6）。

---

## 6. 迁移 V35/V36 + 兼容层 + 回填

### 6.1 迁移拆分（精化 M0 单 V35 骨架，见 §8 M1-6）

| 迁移 | 内容 | 环境 |
|---|---|---|
| **V35__rbac_v2_schema.sql** | §2.1 五表 DDL + `sys_employee.scope_version` + §2.2 `sys_role_scope` 静态配置种子 | 全环境（纯 schema+配置） |
| **V36__rbac_v2_backfill.sql** | `sys_user_role` 由 `sys_employee.role` 经映射填新码（ASSISTANT→MAINT_TECH 合并 D-A6）；`sys_org` 建公司/部门/班组节点（TEAM 由现有 group_name）+ 使用单位单级节点（由 use_unit）；use_unit 账号→`sys_user_role`(user_type=UNIT, CLIENT_ADMIN+CLIENT_SIGNER)（P-3）；org_id 锚定班组/单位节点。**区域仍留独立 region 表，不进 org 树** | dev/test；**生产空库 no-op** |
| **DemoDataSeeder**（守卫 SEED_DEMO_DATA+空库） | **张工 INST_MGR 测试账号**（P-1，不升任张伟 emp_1）+ 种子账号 sys_user_role/org 演示数据 | **仅 test，绝不进 Flyway/生产** |

> 张工是新建测试账号，写进 Flyway 会在生产凭空造账号——故归 DemoDataSeeder。V36 回填类比 V31 department UPDATE（Flyway 数据迁移，空库 no-op），不含任何新建账号。

### 6.2 兼容层（过渡期唯一事实源 = 映射表）

- RoleCatalog 旧→新别名；LegacyCapabilityTranslator 24→71（含 adminConsole 门控、order/fault 反转）。
- `sys_employee.role` 旧码值不改；新码只在 `sys_user_role`；DB 改名推迟 M5。
- 旧 token：`sv=null→0` 不误拒；门户 `UNIT_ADMIN` 别名放行。
- 旧 `@RequirePerm`/MenuService 旧码经翻译器→新矩阵，旧端点/旧菜单行为逐条不变。

### 6.3 安全护栏

- **严禁自动 TRUNCATE/DELETE**：生产清库是一次性人工上线步骤（备份→用户确认→清业务数据保 Flyway schema），不在任何迁移里（AGENTS §6 / 程序 spec §5.3）。
- `MigrationIntegrityTest` 守同版本重复（V3.2）；打包 `mvn clean package`；迁移失败自愈沿用 V3.1（删 flyway_schema_history 失败行重跑）。
- 下一迁移号 V35（V30 已重编 V34，V32 为检查清单提案预留）。

---

## 7. 测试与门禁

### 7.1 兼容证明（字面不动，必须保持绿）

- `AdminRoleInterceptorCompatTest`：5 角色×17 路由旧两档判定逐条不变（M1 硬门禁）。
- 新增旧 token 兼容测试：`sv=null→0` 不误拒、门户 `UNIT_ADMIN` 别名放行。
- MenuService 旧码经翻译器→旧菜单逐条不变。

### 7.2 新增单测（与实现同包路径，AGENTS §4.2）

| 组 | 关键断言 |
|---|---|
| 矩阵 v2 | 三态 state() 正确；fail-closed 启动校验；24 旧常量仍在；allowsNew 要求 Y；71+ 码与 M0 §2.3 导航推导一致 |
| 翻译器 | 24→71 逐码；console:read 的 adminConsole 门控（MT→false）；order/fault 反转；四关键码复现旧授权 |
| RoleCatalog v2 | 旧→新别名；13 角色部门/adminConsole；同部门兼任允许、跨部门拒绝 |
| 多角色+scopeVersion | 权限并集、逐角色范围；scope_version 递增→旧 token 401；ATTR_ROLE 主角色 + ATTR_ROLES 全集 |
| ScopeResolver 等价 | 全旧角色×代表班组/区域：读==visibleReadPlatformIds、写==visibleWorkerPlatformIds 逐集合相等；读写不对称保留；写谓词绝不扩区域/部门 |
| 越权留证 | docs/14 §12 越权验收用例逐条→1403/403 且无副作用；跨部门拒绝、代签拒绝 |
| 限时授权 | 四态生命周期；end>start；回收任务置 REVOKED+递增 scope_version+审计；解析并集生效/脱落；守读写分离 |
| 审计 | append-only 无删改端点；授权/范围变更/BLOCKED 写入；op_log 不受影响；脱敏 |
| 迁移 | V35/V36 干净应用；MigrationIntegrityTest 无重复版本；V36 空库 no-op；张工仅 DemoDataSeeder |

### 7.3 门禁命令（AGENTS §4.4/§7）

- `backend/`：`mvn test` 全绿——**322 基线不回退** + 上述新增；打包 `mvn clean package`。
- 签到/派单/模板链路：`node scripts/verify-dispatch.js`、`node scripts/verify-templates.js`（鉴权改动可能波及，必跑）。
- `admin/` 三件套（`lint && test && build`）：M1 不改 admin 代码，旧端经兼容层照常可用，跑一遍确认零回归。

### 7.4 回归面声明

M1 仅动 backend 鉴权层（`auth/*` + 新增 scope/grant/audit service + V35/V36 + `Employee.scope_version`）；业务查询零改动（Q2）、admin/小程序代码零改动、`sys_employee.role` 旧码值不改。

---

## 8. 与程序 spec / M0 的偏离精化记录

| # | 精化 | 来源 | 需同步 |
|---|---|---|---|
| M1-1 | 程序 spec §9 M1「九表+加列」收窄为「权限核心五表」；域表+域加列推迟 M3/M4/M5 | Q1 裁决 | 程序 spec §9 + §13 |
| M1-2 | ScopeResolver 只建+证等价，不改现有业务查询 | Q2 裁决 | 本 spec §4.5 |
| M1-3 | scope_ref 精化：sys_role_scope 存默认范围枚举+version；逐用户锚点放 sys_user_role.org_id | §1 确认 | 程序 spec §5.1（M1 落地时） |
| M1-4 | console:read 翻译加 adminConsole 门控（防 worker 意外获管理端入口） | §2 确认 | M0 §2.1 row1 补注 |
| M1-5 | M1 不改 sys_employee.role 旧码值，新码只落 sys_user_role，DB 改名推迟 M5 | §3 确认 | M0 §1.2.3 步骤1 补注 |
| M1-6 | V35 schema+config / V36 backfill 拆分；张工归 DemoDataSeeder 不进 Flyway | §5 确认 | M0 §1.2.3 补注 |

---

## 9. 风险

| # | 风险 | 处置 |
|---|---|---|
| 1 | 翻译器精度：console:read/write→新码若映射不准会破坏旧放行 | CompatTest 5×17 逐条守门 + 翻译器单测；adminConsole 门控专项 |
| 2 | order/fault 语义反转误映射 → 维保作业塞进急修 7 态机（Blocker） | 翻译器按 M0 §2.0 方向；专项单测断言反转 |
| 3 | ScopeResolver 与 V7 不等价 → 越权或漏权 | 逐集合相等等价测试 + docs/14 §12 越权用例留证 |
| 4 | 旧 token（无 sv）被误拒 → 线上掉登录 | sv=null→0 兼容 + 专项测试 |
| 5 | 张工/演示数据进 Flyway → 生产凭空造账号 | 张工归 DemoDataSeeder（SEED_DEMO_DATA 守卫）；V36 不含新建账号 |
| 6 | 多角色 scope_version 失效机制误伤单角色旧账号 | 单角色 ATTR_ROLE 逐字不变 + 旧 token 兼容测试 |
| 7 | 限时授权回收任务与派单任务资源竞争 | 沿用 @Scheduled 独立 cron；回收只改 grant 状态+scope_version，不触派单 |

---

## 10. 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| V1.0 | 2026-11-14 | 首版：M1 边界（Q1 权限核心五表 / Q2 解析器只建+证等价）、方案 1 单一新矩阵+翻译、数据模型（五表 DDL+scope_version+scope_ref 精化）、角色目录 v2/矩阵 v2 三态/翻译器（adminConsole 门控+order/fault 反转）、鉴权链 v2（多角色/scopeVersion/旧 token 兼容）、ScopeResolver 读写双谓词+V7 等价证明、限时授权到期回收、业务审计底座、V35/V36 拆分+张工归 Seeder、测试与门禁、6 条偏离精化、7 项风险 |