# P2+P3 客户租户设计：租户快照 + 客户账号独立 + 门户切换（V1.0）

> 2026-11-17。上游蓝图：`docs/14` V2.1—V2.3（§4.6—§4.8、§6、§7、§9—§13）、`docs/12` I 组（P2/P3 行 + 发版注意项 R1/R2）。
> 本文是**实施设计**：蓝图已定的口径不重复论证，只写增量裁决、精确 schema、端点契约、Runner 规格与切换顺序。与蓝图措辞的偏离均在 §19 显式登记。
> 命名消歧：本文 P2/P3 指 docs/14 §12 分期（P2=租户/设备快照 V47，P3=客户账号独立 V48/V49）；与已完成的「权限 P2 待办轮」（C-24/C-25，plans/2026-11-17-permission-p2.md）无关。

## 0. 用户已裁决（本设计的输入，不再重开）

1. **P2+P3 一体设计**，实施分两阶段（P2 可独立先上）。
2. **迁移执行器**：Flyway 只做 DDL；数据迁移/回填由启动期 Java 幂等 Runner 执行，冲突输出清单表人工核对。
3. **selectionToken**：落库一次性 token（哈希存储、条件更新消费、purpose 隔离），不做无状态 JWT。
4. **切换策略**：后向兼容登录响应（只增不改）+ P3/P4 同批上线 + 旧 `/unit/**` ≥30 天兼容窗口，**不设环境开关**。

## 1. 意图与范围

P1（已完成，581 单测基线）给了客户主体**语义与安全门禁**，但客户账号仍寄生在 `sys_employee`（V41 合并形态），租户归属靠「电梯当前 use_unit_id」实时反查。本设计落地：

- **P2**：`maintain_record/fault/work_order` 冻结租户快照（V47），记录继承工单、修订继承首版、归档 PDF 用快照；存量回填 + 过户嫌疑人工核对。
- **P3**：客户账号迁出员工表进 `client_account` 独立表（V48），审计补租户列（V49）；登录选单位（selectionToken）；`/client/**` 端点族；客户文件租户绑定；签字人快照落库 + 签字审计（I 组验收项）；GRACE/DISABLED 生命周期；内部客户账号管理端点；报修 API 收敛（C-25①）。
- **P4（同批清单，§15）**：管理端/小程序/mock/文档切换面，本文只列清单不展开（细节见 docs/14 §10.2/§10.3/§10.4）。

## 2. 目标 / 非目标 / 反目标

**目标**：客户数据只认冻结快照与 token `useUnitId`；客户账号彻底离开员工/组织/统计；一次登录一个最终 token；签字留双份证据（记录列 + 审计）；所有迁移幂等、失败不写半成品、冲突不静默猜。

**非目标**（本期不做）：客户自助账号管理（`client:user_manage` 保持 DENY）；客户看合同/移交/报修/评价；新签发 shareToken；迁移期双写；删旧表/旧列（生产清库是独立人工步骤）；C-26 NULL 谓词统一治理（客户域不走 worker-scope，无交集，仍独立待办）。

**反目标**（出现即失败）：客户出现在员工列表/组织树/统计；内部 token（含平台管理员）能签客户字；客户签字/拒签触发 2.6 或改上报快照；跨租户文件可读；人工/迁移停用账号被合同聚合自动恢复；回填把过户电梯历史记录静默归给当前单位。

## 3. 约束（红线，逐条继承）

AGENTS §2 全部红线；§3.9 单账号单会话（客户 `client_account.session_id` 同员工口径）；Flyway 只追加、不改已执行迁移；`/unit/login*` 废弃链契约标注保留至生产清库；客户签字不触发 2.6（§3.5）；文件签名不落日志；迁移不 DELETE 任何行（留存红线，`sys_user_role` 客户行保留不复用）。

## 4. 数据模型（V47 / V48 / V49，只追加）

### V47__tenant_snapshot.sql（P2，仅 DDL）

```sql
ALTER TABLE maintain_record
  ADD COLUMN use_unit_id   VARCHAR(32)  NULL COMMENT '生成时冻结的租户归属（P2 快照）',
  ADD COLUMN use_unit_name VARCHAR(128) NULL COMMENT '生成时冻结的单位名称（PDF/归档用）',
  ADD COLUMN elevator_id   VARCHAR(32)  NULL COMMENT '生成时冻结的电梯 id';
ALTER TABLE fault
  ADD COLUMN use_unit_id   VARCHAR(32)  NULL COMMENT '登记时冻结的租户归属',
  ADD COLUMN use_unit_name VARCHAR(128) NULL COMMENT '登记时冻结的单位名称';
ALTER TABLE work_order
  ADD COLUMN use_unit_id   VARCHAR(32)  NULL COMMENT '派单时冻结的租户归属',
  ADD COLUMN use_unit_name VARCHAR(128) NULL COMMENT '派单时冻结的单位名称';

-- 快照谓词索引随列同批（P2b 客户查询即依赖；不能推迟到 V49——P2 先于 P3 独立上线，§19-22）
CREATE INDEX idx_record_unit ON maintain_record (use_unit_id);
CREATE INDEX idx_fault_unit  ON fault (use_unit_id);
CREATE INDEX idx_order_unit  ON work_order (use_unit_id);

-- 回填/迁移人工核对清单表：P2a 回填 Runner 即写入，随 V47 建（原排 V49，§19-22 修订）
CREATE TABLE client_migration_issue (
  id                VARCHAR(32) PRIMARY KEY,
  issue_type        VARCHAR(24) NOT NULL COMMENT 'DEDUP_CONFLICT/NO_ANCHOR/CROSS_PRINCIPAL/MIXED_SKIPPED/TRANSFER_SUSPECT/CODE_AMBIGUOUS/UNMATCHED',
  object_type       VARCHAR(16) NOT NULL COMMENT 'ACCOUNT/RECORD/FAULT/ORDER',
  object_id         VARCHAR(64) NOT NULL,
  elevator_code     VARCHAR(64) NULL,
  current_unit_id   VARCHAR(32) NULL,
  candidate_unit_id VARCHAR(32) NULL,
  evidence          VARCHAR(500) NULL COMMENT '证据摘要（工单 id/合同 id/change id/冲突双方）',
  confidence        VARCHAR(8)  NULL COMMENT 'HIGH/MID/LOW',
  resolution        VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/BACKFILLED/SKIPPED/MANUAL_DONE',
  decision_json     TEXT NULL COMMENT '人工裁定（DEDUP_CONFLICT 取源/NO_ANCHOR 补锚点），Runner 重跑消费（§19-15）',
  resolve_note      VARCHAR(255) NULL,
  resolved_by       VARCHAR(32) NULL,
  resolved_at       DATETIME NULL,
  created_at        DATETIME NOT NULL,
  UNIQUE KEY uk_issue (object_type, object_id, issue_type),
  KEY idx_issue_pending (resolution, issue_type)
) COMMENT='P2 回填 / P3 账号迁移的人工核对清单（幂等 upsert）';
```

### V48__client_account.sql（P3）

```sql
ALTER TABLE sys_employee
  ADD COLUMN principal_type VARCHAR(24) NOT NULL DEFAULT 'EMPLOYEE'
    COMMENT 'EMPLOYEE/CUSTOMER_ARCHIVED（P3；客户迁出后旧行标 CUSTOMER_ARCHIVED）';

CREATE TABLE client_account (
  id            VARCHAR(32) PRIMARY KEY COMMENT 'ca_ 前缀',
  use_unit_id   VARCHAR(32) NOT NULL COMMENT '租户锚点：use_unit.id',
  name          VARCHAR(64) NOT NULL,
  phone         VARCHAR(64) NOT NULL COMMENT '登录标识；承接旧门户 account 字符串（可能非 11 位）',
  password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  openid        VARCHAR(128) NULL,
  role_code     VARCHAR(32) NOT NULL COMMENT 'CLIENT_ADMIN/CLIENT_SIGNER',
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/GRACE/DISABLED（事实源）',
  grace_until   DATETIME NULL,
  enabled       TINYINT NOT NULL DEFAULT 1 COMMENT '旧字段兼容：ACTIVE/GRACE=1、DISABLED=0',
  session_id    VARCHAR(64) NULL COMMENT '单会话',
  scope_version INT NOT NULL DEFAULT 1,
  source_ref    VARCHAR(64) NULL COMMENT '迁移来源 emp:{id} / unit:{useUnitId}（幂等+溯源，本设计新增）',
  created_by    VARCHAR(32) NULL,
  created_at    DATETIME NOT NULL,
  updated_at    DATETIME NULL,
  disabled_at   DATETIME NULL,
  disabled_reason VARCHAR(64) NULL COMMENT 'MANUAL*/MIGRATED_DISABLED/MIGRATED_NO_CONTRACT/GRACE_EXPIRED',
  last_login_at DATETIME NULL,
  active_phone  VARCHAR(64) GENERATED ALWAYS AS
    (CASE WHEN status <> 'DISABLED' THEN phone ELSE NULL END) STORED,
  active_openid VARCHAR(128) GENERATED ALWAYS AS
    (CASE WHEN status <> 'DISABLED' THEN openid ELSE NULL END) STORED,
  UNIQUE KEY uk_client_unit_phone (use_unit_id, active_phone),
  UNIQUE KEY uk_client_unit_openid (use_unit_id, active_openid),
  KEY idx_client_phone (phone),
  KEY idx_client_openid (openid),
  KEY idx_client_unit (use_unit_id)
) COMMENT='使用单位客户账号（非员工）';

ALTER TABLE maintain_record
  ADD COLUMN client_account_id          VARCHAR(32)  NULL COMMENT '客户签字账号',
  ADD COLUMN client_signer_name         VARCHAR(64)  NULL COMMENT '签字人姓名快照',
  ADD COLUMN client_signed_at           DATETIME     NULL,
  ADD COLUMN refused_by_client_account_id VARCHAR(32) NULL COMMENT '拒签账号',
  ADD COLUMN client_refuser_name        VARCHAR(64)  NULL COMMENT '拒签人姓名快照',
  ADD COLUMN client_refuse_reason       VARCHAR(500) NULL COMMENT '客户拒签原因（与内部复核 refuse_reason 分列）',
  ADD COLUMN client_refused_at          DATETIME     NULL;

CREATE TABLE client_selection_token (
  id              VARCHAR(32) PRIMARY KEY,
  token_hash      CHAR(64) NOT NULL COMMENT 'SHA-256(token 明文)；明文只回传客户端',
  purpose         VARCHAR(16) NOT NULL COMMENT 'LOGIN/RESET_PASSWORD，不可互换',
  candidates_json TEXT NOT NULL COMMENT '[{accountId,useUnitId,unitName,roleCode,status}]，仅口令/openid 验证通过的账号',
  expires_at      DATETIME NOT NULL COMMENT '签发 +5 分钟',
  consumed_at     DATETIME NULL COMMENT '条件更新一次性消费',
  created_at      DATETIME NOT NULL,
  UNIQUE KEY uk_sel_token (token_hash)
) COMMENT='多单位登录/重置选择凭证（一次性）';
```

生成列注意：云托管 MySQL 5.7（≥5.7.6 支持 STORED 生成列与其唯一索引，V22 时代已验证 5.7 方言）；若目标实例不支持，回退方案为去掉生成列、改「应用层插入/更新前冲突校验 + 普通联合索引」，Runner 与 `ClientAccountService` 校验逻辑不变（§19-3）。

### V49__client_audit_and_backfill_helper.sql（P3）

```sql
ALTER TABLE sys_audit_log
  ADD COLUMN use_unit_id VARCHAR(32) NULL COMMENT '客户租户；内部审计为空（不复用 org_id）',
  ADD COLUMN actor_type  VARCHAR(16) NULL COMMENT 'EMPLOYEE/CUSTOMER（现表无此列，蓝图§7.4 语义需要，本设计新增）';

CREATE INDEX idx_audit_unit    ON sys_audit_log (use_unit_id);
CREATE INDEX idx_emp_principal ON sys_employee (principal_type);
-- 快照三索引与 client_migration_issue 表已移至 V47（P2a 先行依赖，§19-22）
```

## 5. P2 运行期逻辑（快照写与读）

| 触点 | 规则 |
|---|---|
| 派单（`DispatchService`，自动+手动） | `work_order.use_unit_id/use_unit_name` = 电梯**派单时**归属 |
| 签退生成记录 | `maintain_record` 三列**优先继承工单快照**；工单快照 NULL 时先查过户嫌疑集（§6.1 的 S）：命中 → 置 NULL 并写 `TRANSFER_SUSPECT` issue（防在途工单把历史静默归给新单位，§19-16）；未命中 → 回退电梯当前归属 + WARN 日志 |
| 急修登记（`FaultService`，仅内部登记；客户报修已收敛 §9.3） | `fault.use_unit_id/use_unit_name` = 电梯登记时归属 |
| 修订（`WorkDomainService.amend`） | v2 复制 v1 的 `useUnitId/useUnitName/elevatorId`，**不重读电梯当前归属**（现状确认未复制） |
| 归档/PDF（`RecordLifecycleService.archive` / `RecordPdfService`） | 单位名称用 `record.use_unit_name` 快照；NULL → PDF 内部标注「归属待核对」。**该快照分支随 P2b 上线**（P2a 期间客户侧 PDF 仍按当前归属回退，防未回填期客户可见回归，§19-17） |
| 客户读路径 | 记录/急修按 `use_unit_id = token.useUnitId` 过滤（替代现 `unitOf()` 电梯当前归属链）；电梯列表仍按当前归属（蓝图 §13.9 的 UI 口径差异保留） |
| NULL 快照 | 客户不可见；内部端点/管理端可见并带「待核对」标记 |

**P2 内部两步上线（重要裁决，§19-1）**：
- **P2a**：V47 DDL + 写入链路（派单/签退/急修/amend）+ 回填 Runner + issue 人工核对（含最小核对视图，§6.6）。**客户读路径与客户侧 PDF 不动**（仍按电梯当前归属）——避免回填未完成期间客户待签列表缺行、PDF 单位名空白。
- **P2b**：`client_migration_issue` 中 RECORD/FAULT 类 PENDING 清零（BACKFILLED/SKIPPED/MANUAL_DONE）后，客户读路径切快照谓词 + 归档/PDF 切快照单位名（移除当前归属回退）。切换是纯代码发布，可独立回滚。

## 6. 回填 Runner（P2，`TenantSnapshotBackfillRunner`）

启动期执行（`@PostConstruct`，先例 `UnitAccountUnifyService`），幂等：只处理 `use_unit_id IS NULL` 行；issue 按 `uk_issue` upsert。

1. **过户嫌疑集 S**：`biz_elevator_change` 中 `change_type='TRANSFER' AND status='CONFIRMED'` 的 `elevator_id` 集。S 内电梯的**全部历史** record/fault/work_order 不自动回填，逐行写 `TRANSFER_SUSPECT` issue（confidence=LOW）。
2. **work_order**：按 `elevator_id → elevator.use_unit_id`（HIGH，自动回填）。elevator 缺失 → `UNMATCHED` issue。
3. **maintain_record**：优先 `work_order_id → work_order.use_unit_id`（回填后）或 `work_order.elevator_id → elevator`（HIGH）；无工单关联的存量按 `elevator_code → elevator`——同码多行（C-28 无唯一键）时按 `ORDER BY id` 取首行回填并写 `CODE_AMBIGUOUS` issue（MID）；无法确定 → NULL + `UNMATCHED`。
4. **fault**：按 `elevator_code`，同上。
5. `use_unit_name` 随 `use_unit_id` 从 `use_unit.unit_name` 写入。
6. **人工核对载体与裁定机制（§19-15/§19-21）**：P2a 交付**最小管理端核对视图**（admin 复用 DataTable 列表 + resolve 对话框；门控=平台域管理 canonical 码（SYS_ADMIN/PLATFORM_ADMIN 可达；无 `platform:manage` 码，落地按矩阵核实取码）；操作者=平台管理员/维保经理）：
   - `GET /admin/migration-issues?type=&resolution=`（分页列表；清单不含敏感金额，不走敏感导出审批）；
   - `POST /admin/migration-issues/{id}/resolve` `{resolution, backfillUnitId?, decision?, note}`（单条、写审计；`BACKFILLED` 按指定单位回填该行；**不提供批量 resolve**）；
   - `decision` 为 ACCOUNT 类 issue 的机器可读裁定，存 `decision_json` 供迁移 Runner 重跑消费——`DEDUP_CONFLICT`：`{passwordSource:'EMP'|'UNIT', openidSource?:'EMP'|'UNIT'}`；`NO_ANCHOR`：`{anchorUnitId}`；`MANUAL_DONE`=已在带外人工完成（Runner 对该源永久跳过）；`SKIPPED`=裁定不迁（源行保持旧路径至窗口结束）。生产库无直写通道（docs/11 §8 背景），全部裁定经 API 完成。

## 7. 账号迁移 Runner（P3，`ClientAccountMigrationRunner`）

**前置**：`UnitAccountUnifyService` 移除 `@PostConstruct`（类保留、`@Deprecated` 标注「P3 起由 ClientAccountMigrationRunner 取代，禁止再向 sys_employee 写客户行」），防止启动期把客户账号重新并入员工表。Runner 顺序在其后（`@Order` 或依赖注入保证）。

**幂等**：目标行存在性按 `source_ref` 判重；重复启动跳过已迁账号（status 重算除外——status 由合同聚合驱动，重跑安全）。

**源与锚点**（两种历史形态都要处理）：
1. `sys_user_role` `user_type='UNIT'` 行（V36 P-3 产物）：`user_id` 即 `use_unit.id`，凭证在 `use_unit.account/password_hash/openid`。
2. `sys_employee` 主角色或 `sys_user_role(user_type='EMPLOYEE')` 行含 `CLIENT_ADMIN/CLIENT_SIGNER`（V36 映射 + V41 合并产物）：锚点优先 `use_unit.account = emp.account` 精确匹配；不中再看 `sys_user_role.org_id`（`ou_` 锚点为 MD5 派生**不可逆**，需经 `sys_org` UNIT 节点的源单位字段回查——builder 落地时核实 `sys_org` 是否存有 `use_unit.id` 引用，若无则该路径直接进 `NO_ANCHOR` issue，不猜）；再不中 → `NO_ANCHOR` issue，不迁移。
   - **混合角色行排除（§19-13a）**：员工行生效角色集含**任一内部角色**（V41 同账号合并产物，P1 终审 I2 登记的历史形态）→ **不归档、不迁移**（员工身份必须保留，整行归档会停用真实维保人员），写 `MIXED_SKIPPED` issue 留痕观察；其客户角色随「混合角色按员工处理」既有口径自然失效（内部矩阵无客户码 Y，登录响应 principalType=EMPLOYEE）。只有**纯客户行**（生效角色全部为 CUSTOMER 规范码）进入迁移与归档。

**去重**（蓝图 §9.2.2）：同 `(use_unit_id, phone)` 多源 → 合并为一个账号：角色优先 `CLIENT_ADMIN`；口令取非空者，两源口令哈希不同 → `DEDUP_CONFLICT` issue，**该账号不自动迁**，人工裁定后重跑；openid 取非空优先，双非空不同 → 取 CLIENT_ADMIN 源 + issue 留痕。

**员工/客户跨表互斥**：迁移账号 phone/openid 与 `principal_type='EMPLOYEE'` 且 enabled 的员工行冲突 → 照常迁移（登录先查员工后查客户，员工命中即屏蔽客户，无歧义），但写 `CROSS_PRINCIPAL` issue 提示人工决定保留哪个主体。

**status 重算**（逐账号，蓝图 §9.2.2/§6.3）：
- 旧 `enabled=0` → `DISABLED`（`MIGRATED_DISABLED`），不因待签自动恢复；
- 单位存在有效合同（`status='ACTIVE' AND end_date >= CURDATE()`）→ `ACTIVE`；
- 合同全部到期/终止 → `GRACE`，`grace_until` 按 §12 公式；
- 无合同：单位存在 `PENDING_CLIENT_SIGNATURE` 记录 → `GRACE`（`grace_until = max(待签记录 created_at) + 30d`）；无待签 → `DISABLED`（`MIGRATED_NO_CONTRACT`）。

**旧行归档（不 DELETE）**：源 `sys_employee` 行置 `principal_type='CUSTOMER_ARCHIVED'`、`enabled=0`、`department=NULL`、清 `session_id/session_admin`；`sys_user_role` 客户行（两种形态）原样保留、不再被任何鉴权路径消费；`use_unit.account/password_hash/openid` 保留只读，`account_migrated` 语义冻结；旧 `UNIT_ADMIN` 主角色员工行同样归档。

**员工/组织/统计切换**：`EmployeeService/UserRoleService/OrgService/StatsService` 统一叠加 `principal_type='EMPLOYEE'` 过滤；**P1 的 role 临时排除口径在迁移空窗期保留**——Runner 裁定后仍未迁出的纯客户员工行（DEDUP_CONFLICT/NO_ANCHOR）principal_type 仍为 EMPLOYEE，需 role 谓词兜住才不进员工列表/组织树/统计（反目标）；两口径并存仅限空窗期，移除条件=纯客户员工行清零（§19-14）。`RoleCatalog.assignableRoles` 已按 principalType 过滤（P1），不动。

## 8. 登录与会话（P3）

### 8.1 登录链（`AuthController`）

`POST /auth/login`（响应**只增不改**）：
1. `sys_employee` 按 account 查（限 `principal_type='EMPLOYEE'`；CUSTOMER_ARCHIVED 行 enabled=0 天然拒绝）→ 命中走现有员工链，响应不变。
2. 未命中 → `client_account` 按 phone 查全部非 DISABLED 行，逐行 BCrypt 校验：
   - 0 行通过 → `401`（与员工错口令同文案，不泄露主体类型）；
   - 1 行通过 → 签发客户 token + 客户版登录响应（新增字段：`principalType:'CUSTOMER'`、`useUnitId`、`unitName`、`clientAccount:{id,name,roleCode,status,graceUntil}`；员工专属字段 roles/perms/activeWorkspace/home 按客户最小集下发，见 §8.3）；
   - ≥2 行通过 → `200 {needUnitSelection:true, selectionToken, units:[{accountId,unitName,roleCode,status}]}`——**只含验证通过的账号**；请求带 `clientVersion` 缺失（旧版小程序）时 → `422 '请更新小程序后登录'`（§8.4）；
   - DISABLED 行不参与校验；全部命中行均 DISABLED → `403 '账号已停用'`。
3. `client=admin` 且命中客户 → `403`（客户不得进管理端）。

`POST /auth/client/select-unit` `{selectionToken, accountId}`：条件更新消费（`UPDATE … SET consumed_at=NOW() WHERE token_hash=? AND consumed_at IS NULL AND expires_at>NOW()`，受影响行 0 → `401 '验证已失效，请重新登录'`）；校验 token purpose=LOGIN、accountId ∈ candidates；**重新查库校验**账号 `status/use_unit_id/role_code/scope_version`（不复用签发时快照）；DISABLED → 403；签发最终 token。

`POST /auth/wx-login`：openid → 员工（现链不变）→ 未命中查 `client_account`（同 phone 逻辑：0/1/多，多单位同走 selectionToken，purpose=LOGIN）。

`POST /auth/reset-password-by-wechat`：双主体。客户命中多单位 → `needUnitSelection` + selectionToken（**purpose=RESET_PASSWORD**）→ `POST /auth/client/reset-password` `{selectionToken, accountId, newPassword}`（独立端点，不与 select-unit 互换；新密码 ≥6 位；重置后清该账号 `session_id`；回传 account 供登录预填）。

`POST /auth/bind-wechat`（登录后）：客户分支——openid 写入当前 `client_account`；同单位已被其他账号占用 → `409`；跨单位允许；该 openid 已被**员工**持有 → `409 '该微信已绑定员工账号'`。员工分支不变（先解绑他人）。

`/auth/me`、`/auth/change-password`、`/auth/logout`：按 token `principalType` 分支操作 `client_account`（不查员工表）；`/auth/me` 客户返回白名单（单位名/账号姓名/roleCode/status/graceUntil），不返回内部角色/部门/perms 全量。`/auth/menus`、`/auth/bind-employee`、`/auth/bind-use-unit`：客户 token `403`。

### 8.2 Token 与拦截器

客户 JWT claims：`sub=ca_xxx, principalType:'CUSTOMER', useUnitId, roles:[roleCode], scopeVersion`（蓝图 §7.2 逐字）。`AuthInterceptor`：
- `principalType=CUSTOMER` → 每请求查 `client_account`：`session_id` 匹配、`scope_version` 匹配、`status`——DISABLED 或会话不符 → `401`；GRACE → 放行但写入口收敛（§12）；白名单：**`/client/**`** + 自身账号端点（`/me/menus`、`/auth/me|change-password|bind-wechat|logout`）+ `/health` + **`GET /elevators*`（沿用 P1 既有契约：客户分支走 `profileForUnit` 本单位谓词、`ElevatorClientProfileTest` 锁定；客户电梯列表/详情页复用员工端点，**不另建 `/client/elevators/{id}` 详情端点**，蓝图 R3 例外面据此增补，§19-18）** + **兼容窗口内 `/unit/**`（仅 GET + sign/refuse 相关 POST，`POST /unit/faults` 403）**；其余一律 `403` + BLOCKED 审计。
- `principalType=EMPLOYEE`（含旧 token 无 claim → 按员工兼容）→ 现有链不变 + **`/client/**` 一律 403**。
- **P1 临时分支处置（§19-14）**：ut=1 旧门户令牌分支自 P3 起删除，直接 `401 '请重新登录'`（门户账号已全部迁入 client_account，旧令牌无主体；§19-6）；`isCustomer(roles)` 角色门禁**保留为迁移空窗兜底**——DEDUP_CONFLICT/NO_ANCHOR 未迁出的纯客户员工行仍走 emp token，靠角色判定进 `/unit/**` 旧路径并挡住内部端点（这些行 issued token 的 principalType 按 P1 T8-A2 角色口径仍算 CUSTOMER，claim 与员工分支不分裂）；移除条件=纯客户员工行清零（断言 SQL：`principal_type='EMPLOYEE'` 且生效角色全 CUSTOMER 的行数=0），清零后独立小提交移除，蓝图「不留双口径」相应顺延至清零时点。
- 单会话：客户登录清旧 `session_id`；重置/停用/换绑/恢复操作同样清会话 + `scope_version+1`。

### 8.3 客户登录响应与菜单

`/me/menus` 客户分支沿用 P1 收敛（待签+我的电梯 quickActions、badges.pendingSigns 本单位可签口径），数据源从「员工表+role 判定」换为 `client_account`；GRACE 账号菜单附只读提示字段（前端横幅）。

### 8.4 旧版小程序兼容（裁决 4 的落地细节）

- 单单位客户：登录响应形状与 P1 完全一致（新增字段旧版忽略），**零感知**。
- 多单位命中仅可能出现在迁移后（同 phone 多单位账号），旧版不识别 `needUnitSelection` → 后端对缺 `clientVersion` 的请求返回 `422`；小程序新版 `app.js` 接入 `wx.getUpdateManager().applyUpdate()` 强制刷新，把窗口压到一次冷启动。
- `services/auth.js` 登录/wx-login/忘记密码请求体统一加 `clientVersion`（取 `wx.getAccountInfoSync().miniProgram.version || 'dev'`）。

## 9. `/client/**` 端点契约（P3 新增，全部客户 token、单位 id 只从 token 取）

### 9.1 门户与记录

| 端点 | 说明 |
|---|---|
| `GET /client/me` | 单位信息 + 账号（name/roleCode/status/graceUntil）；不含合同金额/内部组织 |
| `GET /client/elevators` | 本单位电梯（当前归属 `elevator.use_unit_id`），字段同 P1 `/unit/elevators` 白名单 |
| `GET /client/records?tab=pending|history` | 快照谓词 `record.use_unit_id=token.useUnitId` + 最新版本（`RecordVersionUtil.supersededIds` 复用）+ §4.6 可见状态集；pending=PENDING_CLIENT_SIGNATURE 且 confirm_status=PENDING |
| `GET /client/records/{id}` | `toClientMap` 白名单（§4.7 逐字）+ `version/recordVersion/hashPrefix/archivedAt`；**不存在/跨租户/非最新/不可见状态统一 `404 '记录不存在或不可见'`**（登录态同样消除存在性 oracle，承接 C-25⑧ 治理方向；§19-19）。sign/refuse 写端点在可见性门禁通过后保留状态/版本/先签为准的 `422` 语义（仅作用于已可见记录，前端需区分，不构成 oracle） |
| `GET /client/records/{id}/sign-view` | 签字页投影：状态/版本/hashPrefix/双签状态/签字图；**不返回 shareToken**；错误口径同详情（统一 404） |
| `POST /client/records/{id}/sign` | body `{signatureBase64}`（内联落库为签名文件，替代旧 signatureFileId 链路，§19-8）；门禁链 §4.6.2 逐层（principalType→租户→状态→confirm→最新版本→账号 ACTIVE/GRACE）；条件更新先签为准（受影响行 0 → 422）；落 `client_account_id/client_signer_name/client_signed_at` + `confirm_status=CONFIRMED` + `onClientSigned`（SIGNED→归档）；写审计（§11）；**不触发 2.6** |
| `POST /client/records/{id}/refuse` | body `{reason}` 必填；`REFUSED_RETURNED` + `client_refuse_reason`（不写内部 `refuse_reason`）+ 拒签人快照三列；条件更新同 sign；审计 |
| `GET /client/records/{id}/pdf` | 租户+可见状态校验 → 归档 PDF（`archive_file_id`）流式下发或按快照现场生成；`Content-Disposition` 带记录号 |

### 9.2 文件（§10 详述）

| 端点 | 说明 |
|---|---|
| `GET /client/records/{id}/file/{fileId}` | 鉴权代理：记录租户/可见状态校验 + `fileId ∈ 该记录字段集`（items_json/photos_json/签名/归档字段解析）→ 流式下发（本地→COS 回源复用 `FileStorageService`）；小程序 `wx.downloadFile` 带 Authorization 下载后本地展示 |

### 9.3 明确不提供（收敛项）

- **无 `/client/faults`**：报修默认关闭（蓝图 V2.0 用户裁决）；兼容窗口内 `POST /unit/faults` 对客户 token `403`（C-25① 闭环，矩阵 DENY + 端点层双保险）；`GET /unit/faults` 只读保留至窗口结束。
- 无评价端点（`satisfaction` 不接收，P1 已断）；无账号管理端点（`client:user_manage` DENY）；无合同/移交端点；无 by-token 匿名端点（`/client/**` 不消费 shareToken）。

### 9.4 内部客户账号管理端点（P3，管理端消费）

`ClientAccountController`（`MultiRoleGate` 门控 `useunit:write`/`client:edit`/`client:invite`，全部写审计）：

| 端点 | 说明 |
|---|---|
| `GET /use-units/{id}/client-accounts` | 列表：status/graceUntil/roleCode/last_login_at/待签数 |
| `POST /use-units/{id}/client-accounts` | 开通：name/phone/roleCode/初始口令（≥6）；同单位活跃 phone 唯一 + 跨表互斥校验（冲突 422/409） |
| `POST /client-accounts/{accountId}/disable` \| `/enable` | 停用（reason 必填，`MANUAL_` 前缀）/启用（校验活跃唯一键冲突）；清会话+scope_version+1 |
| `POST /client-accounts/{accountId}/reset-password` | 新口令 ≥6；清会话 |
| `POST /client-accounts/{accountId}/restore-grace` | 限时恢复：`{graceUntil, reason}` 必填、单条、审计；仅 DISABLED→GRACE，不恢复写能力 |
| `POST /client-accounts/{accountId}/unbind-wechat` | 内部强制解绑（换人场景）；清会话 |

`UserController` 同步收紧：禁止创建/修改 `CLIENT_*` 角色员工（P1 已有 assignableRoles 过滤，补写路径断言）。旧 `POST /unit/accounts`（管理端开通门户账号）废弃 → `410`。

## 10. 客户文件租户绑定

三层，蓝图 §4.8 的落地形态：

1. **代理优先**：`GET /client/records/{id}/file/{fileId}`（§9.2）——校验精确到「该记录的字段集」，无全局 file→unit 反查表（记录 JSON 反查代价高且孤儿文件不可判，§19-7）。
2. **URL 兜底（`<image>` 直连场景）**：客户版短 TTL 签名——新 `ClientFileAccessTokenService`，CONTEXT=`|client-file-v1`（与内部 `|file-access-v1` 密钥域隔离，互相不可伪造），payload `fileId:useUnitId:expiry`，TTL **600s**；**只在客户记录详情/sign-view/PDF 组装上下文内签发**（签发时记录租户已校验，归属由上下文保证）。`GET /files/{id}?s=` 按 CONTEXT 区分验签：客户版校验签名+过期+签名内 useUnitId 与请求无关绑定（token 只对所签 fileId 有效）；不做 file→unit 全局反查（蓝图 §4.8.2「复核文件归属」以实现为「签发端上下文保证」替代，偏离登记 §19-7；已泄露 URL 在 10 分钟窗口内仍是能力链接——蓝图 §13.10 已承认该边界）。
3. **客户上传**：仅签字图，内联 base64 走 sign 端点（§9.1），服务端落库生成 file 行并即知其归属记录；客户不调用 `POST /files/upload`（403，P1 已断）。

内部员工文件链路（`/files/{id}?s=` 内部签名、`FileStorageService.urlOf` 重签）**不变**。

## 11. 签字快照与审计（I 组验收项，P1 缺位的两项在此闭环）

- **记录列快照**：sign/refuse 落 `client_account_id + client_signer_name/client_refuser_name + client_signed_at/client_refused_at`（§9.1）；账号后续改名/停用不影响历史展示（R13）。
- **审计双写**（`AuditLogService` 扩展，同事务）：`action=CLIENT_SIGN/CLIENT_REFUSE`，`actor_type=CUSTOMER`、`actor_id=ca_id`、`actor_role=roleCode`、`use_unit_id`、`target=record:{id}`、before/after 快照含 `version/lifecycleStatus/confirmStatus`、result=SUCCESS；门禁失败 → BLOCKED 留痕（含跨租户尝试）；客户路由 403（拦截器）同样 BLOCKED + `use_unit_id`。
- 账号生命周期操作（开通/停用/启用/重置/解绑/恢复宽限）与迁移 Runner 的人工 resolve 全部写审计。
- **兼容期存量匿名签字**（`/unit/records/{id}/confirm-by-token|refuse-by-token`，窗口内既有 shareToken 链接）：无账号主体——`client_account_id` 落 NULL、**不虚构签字人快照**；审计 `actor_type='ANONYMOUS'`、`actor_id='st_'+share_token 前 8 位`、`use_unit_id=记录租户`、reason=`COMPAT_SHARE_TOKEN`。I 组验收项「签字快照+审计」由 `/client/**` 登录态路径完整满足；匿名路径的留痕粒度作为兼容期已知限制登记（§19-20），随窗口结束消亡。
- 回归断言：签字前后 `reg_upload_log` 无新增、`report_payload_json` 不变（蓝图测试 20）。

## 12. GRACE 生命周期（`ClientLifecycleService`）

- **即时重算**：`ContractService` 创建/变更/终止合同后调 `recomputeForUnit(useUnitId)`。
- **每日扫描**：`@Scheduled(cron="0 30 3 * * ?", zone="Asia/Shanghai")`（避开 09:00 派单与 13 分钟兜底；先例 `DispatchService/GrantService`）。只做 `ACTIVE→GRACE`、`GRACE→DISABLED`（到期清会话）；**不从 DISABLED 自动恢复**；`disabled_reason` 以 `MANUAL`/`MIGRATED_` 开头的账号跳过；每次状态变更写审计。
- **grace_until 公式**：`max( 该单位合同 max(end_date) + 30d, 该单位 PENDING_CLIENT_SIGNATURE 记录 max(created_at) + 30d )`；无合同取后项（蓝图「归档日」的可实施近似，§19-5）。GRACE 期间新产生待签记录 → 下次扫描顺延 grace_until。
- **GRACE 能力面**：读/下载/签待签/拒签允许；拦截器拒绝其余写入口（报修/评价本就无端点）；登录响应与 `/client/me` 带 `status=GRACE + graceUntil`，前端只读横幅。
- **临时恢复**：仅 `restore-grace` 内部端点（§9.4），单条、限时、reason 必填、审计；无批量。

## 13. 旧 `/unit/**` 兼容窗口与废弃

| 端点 | P3 上线后 | 窗口结束（≥30 天或旧版占比达标，人工裁决） |
|---|---|---|
| `GET /unit/elevators|records/list|pending-signs|faults`、`GET /unit/records/*`、`GET sign-view` | 客户 token 只读兼容（数据源切 client_account + 快照谓词；`pending-signs` 行内 shareToken 兼容期例外保留——P1 §4.6 规则 8，窗口结束随 410 一并移除） | `410` |
| `POST /unit/records/{id}/confirm|confirm-by-token|refuse-by-token` | 兼容（既有 shareToken 链接可用；**不再签发新 shareToken：`signRequest` 自 P3 后端上线即停写 `share_token`**——用户裁决 §19-13，审核空窗内新记录旧版不可签为已接受代价，靠发布节奏贴合 + updateManager 强更压缩）；已登录客户 confirm 走 client_account 主体校验 | `410` |
| `POST /unit/faults` | **客户 403**（C-25①） | `410` |
| `/unit/login*`、`/unit/accounts`、`/unit/bind-wechat`、`/unit/account/password` | `410 '门户登录已废弃'`（契约标注保留至生产清库，AGENTS §3.9） | 同左 |

发版注意项 R1（C-25⑦ 旧版 orderId→sign-request 1404）与 R2（C-27 匿名 401 不走全局登出）**不在本设计内修**，仍按 docs/12 I 组发版检查单执行；P3 小程序新版发版时 R1/R2 一并核办。

## 14. 服务层落点（后端改造清单的 P3 增量）

- 新：`ClientAccount(entity)/ClientAccountMapper/ClientAccountService/ClientAccountController`、`ClientPortalService/ClientPortalController`、`ClientRecordService/ClientRecordController`（承接 `UnitRecordService/UnitRecordController` 客户逻辑，`toClientMap` 迁移复用）、`ClientLifecycleService`、`ClientFileAccessTokenService`、两个 Runner、`ClientSelectionToken` 存取。
- 改：`AuthController`（双主体+select-unit+reset 选择）、`AuthInterceptor`（§8.2）、`JwtService`（claims 扩展，保留旧 5 参重载兼容）、`MiniappMenuService`（客户分支数据源）、`AuditLogService`（两列）、`RecordLifecycleService`（archive 用快照、signRequest 停写 shareToken、签字审计钩子）、`WorkDomainService.amend`（复制快照）、`DispatchService/OrderService/FaultService`（快照写入）、`ContractService`（重算钩子）、`UnitAccountUnifyService`（停用）、`EmployeeService/UserRoleService/OrgService/StatsService`（principal_type 过滤 + 删 P1 role 临时口径）、`UnitPortalController/UnitRecordController`（降兼容、谓词切快照、POST faults 403）。
- 冻结面不触碰：`RbacMatrixTest/AdminRoleInterceptorCompatTest/LegacyCapabilityTranslatorTest` 锁定的内部授权逐格不变；内部员工 86 码矩阵不动（客户矩阵 P1 已收敛为三码，P3 补 `client:record_view/refuse` 两码进客户矩阵——总码数 86→88，前后端镜像 `constants/permission-matrix.js` 同步，§19-9）。

## 15. P4 同批清单（本文不展开，验收对照 docs/14 §10.2—§10.4）

- **管理端**：OrgView 去 UNIT 节点、UsersView 过滤、UseUnitsView 客户账号 Tab（消费 §9.4）、RolesView 客户只读面板、StatsView 排除、记录/导出显示客户签字人快照。
- **小程序**：单位选择页（新增，pkg-client）、`services/unit.js → services/client.js`、签字内联 base64、图片鉴权下载本地展示、GRACE 横幅、`updateManager` 强更、`clientVersion` 上送。
- **mock**：`/client/**` 全路由 + `/auth/client/select-unit` + 登录 `needUnitSelection/selectionToken` + `/unit/**` 兼容行为（POST faults 403）；错误码口径按 C-29 现状（1403）并在脚本注明非真实口径。
- **文档**：docs/01 §3.2、03（客户页结构+选单位页，页数+1）、04（A.12.3 `/client/**` 契约 + 登录扩展 + 410/422 口径）、09、11（P3/P4 同批、回填/回滚、兼容窗口、清库门禁）、12（I 组状态）、16、AGENTS §3.9（「门户账号经 V41 并入员工体系」改写为客户独立租户口径）+ §10 修订记录。

## 16. 测试与验收

- **单元（后端）**：蓝图 §10.5 全 24 项映射为测试清单基线，重点新增——两个 Runner（幂等重跑/去重三分支/锚点回退/status 重算五分支/归档不 DELETE）、selectionToken（一次性消费/purpose 隔离/过期/candidates 外账号 401）、登录双主体（0/1/多命中、DISABLED、client=admin 403、旧版 422）、GRACE 状态机（合同钩子/扫描/不自动恢复/人工恢复）、签字快照+审计双写+不触发 2.6、amend 复制快照、客户文件代理越权、签名域隔离（内部 token 不能签客户 URL、反之亦然）、拦截器白名单矩阵（客户/员工/ut=1 三分支）、混合角色行 MIXED_SKIPPED 不归档、`decision_json` 裁定消费重跑、匿名签字 ANONYMOUS 审计留痕、读端点 404 统一口径、在途工单过户嫌疑置 NULL。
- **验收**：docs/14 §11 全 35 条为人工验收基线（P3 相关 #1—11、18—35；#27/28 多单位为本批新能力）；`verify-p2p3-mock` 新脚本（仿 verify-p1-mock：mock 契约断言 + 白名单/门禁/字段白名单逐项）。
- **门禁命令**：backend `mvn clean test`；根 `npm run lint`；`node scripts/verify-{dispatch,templates,routes,miniapp-m0,m1-sync,review-fixes,p1-mock,p2p3-mock}.js`；admin 三件套（P4 触碰时）；automator 走查按 IDE 可用性，不可用转人工门禁（先例 V3.29）。

## 17. 上线顺序 / 回滚 / 生产人工门禁

**顺序**：
1. **P2a**：V47 → 快照写入代码 + `TenantSnapshotBackfillRunner` + 最小核对视图 → 部署 → 人工核对（TRANSFER_SUSPECT/CODE_AMBIGUOUS/UNMATCHED 逐条 resolve）。
2. **P2b**（RECORD/FAULT issue 清零后独立发布）：客户读路径切快照谓词 + 归档/PDF 切快照单位名（移除当前归属回退）；`amend` 复制已在 P2a（写入侧无行为回归）。
3. **P3+P4 同批**：V48/V49 → `UnitAccountUnifyService` 停用 + `ClientAccountMigrationRunner` → 后端全量（登录双主体/拦截器/`/client/**`/账号管理/GRACE）→ admin 新版 → 小程序新版发布（updateManager 强更）→ 兼容窗口计时开始。**发布节奏（§19-13 配套）**：小程序新版**先行提审**（与后端准备并行），过审后部署 P3 后端、随即发布小程序并强更——把「shareToken 停发 + 登录响应变更」对旧版的空窗压到小时级。**空窗保护**：后端部署到 ACCOUNT 类 issue 清零之间，未迁出的纯客户员工行由保留的 P1 角色门禁兜底（§8.2，走 `/unit/**` 旧路径），清零是「移除临时门禁 + 小程序放量」的前置，不是后端部署前置；可在预演环境先行演练 DEDUP_CONFLICT/NO_ANCHOR 裁定口径。
4. **窗口结束**（≥30 天且旧版占比达标，人工裁决）：`/unit/**` 关 410、移除 pending-signs shareToken 例外、删拦截器兼容分支。

**回滚**：DDL 无 down（Flyway 只进）；P2b 谓词切换可代码回滚（快照列留存无害）；P3 代码回滚预案——客户已归档进不了旧链，需人工执行恢复 SQL（`principal_type='EMPLOYEE'`、`enabled=1`、还原 department 的**预生成脚本**随发布单备好，写入 docs/11），故 P3 发布窗口选低峰并预演。

**生产人工门禁**（衔接 docs/12 G 组，全部人工、不自动化）：①迁移前数据库备份；②`client_migration_issue` ACCOUNT 类 PENDING 清零是 P3 小程序放量前置；③RECORD/FAULT 类清零是 P2b 前置；④禁止任何自动 TRUNCATE/DELETE（留存红线）；⑤Flyway 失败自愈口径按 docs/11 §8；⑥生产清库（旧列/旧表/废弃链清理）仍是独立人工步骤，不在本设计内。

## 18. 与既有待办的交集处置

| 待办 | 处置 |
|---|---|
| C-25①（报修 API 裁决） | **本设计闭环**：无 `/client/faults`，`POST /unit/faults` 客户 403（§9.3） |
| C-25③⑥（mock 谓词/残留） | 随 P4 mock 重构一并处理 |
| C-26（NULL 谓词+规范码归一） | 不受影响（客户域不走 worker-scope）；仍独立待办，管理岗放量前关闭 |
| C-27 / C-25⑦（发版注意项 R1/R2） | 不在本设计内修；P3 小程序发版时按 I 组检查单核办（§13） |
| C-28（elevator_code 唯一键） | 回填已容错（CODE_AMBIGUOUS 取首行）；唯一键恢复仍独立评估，若先于 P2 落地则回填歧义分支自然失活 |
| C-29（mock 错误码） | 不扩范围；verify-p2p3-mock 按 mock 现口径断言并注明 |

## 19. 本设计裁决登记（我方提案，用户批准设计文档即生效；builder 不得静默改）

1. **P2 拆 P2a/P2b 两步**：客户读路径在回填核对完成前不切快照，防客户可见性回归（蓝图未拆）。
2. **`client_selection_token` 落库表**（蓝图只说短时一次性，未定载体）；token 明文不落库，存 SHA-256。
3. **生成列回退预案**：MySQL 5.7 不支持 STORED 生成列唯一索引时改应用层校验（蓝图自带该注记，本设计定回退形态）。
4. **`source_ref` 列**：client_account 增迁移溯源列（蓝图无），幂等与人工核对都靠它。
5. **grace_until 公式**：以「待签记录 created_at + 30d」近似蓝图「归档日」（待签记录尚未归档，无归档日可取）。
6. **ut=1 旧门户令牌 P3 起直接 401**（蓝图只说 `/unit/login*` 废弃，未定存量令牌处置）。
7. **文件归属校验以「签发上下文 + 记录字段集代理」替代全局 file→unit 反查**（蓝图 §4.8.2 措辞为「复核文件归属」）；不建 file_grant 映射表。
8. **客户签字图内联 base64**（蓝图允许「POST /client/files 或内联」二选一，选内联：callContainer JSON 友好、少一个 multipart 端点）。
9. **客户矩阵补 `client:record_view`/`client:refuse` 两码**（蓝图 §5.4 已列，P1 未落码；总码数 86→88，镜像同步）。
10. **多单位+旧版 → 422 提示更新**，不做 clientVersion 双形状登录响应（蓝图两方案中取强制更新侧，理由：微信 updateManager 窗口极短、多单位账号是迁移后新形态存量极小）。
11. **跨表 phone/openid 冲突照常迁移 + issue 留痕**（登录顺序天然消歧），不阻塞迁移。
12. **`sys_org` UNIT 节点是否存 use_unit 源引用**为 builder 核实项：有则作锚点回退路径，无则 NO_ANCHOR 进人工（§7）。

以下为 builder 审查（2026-11-17）后补充的裁决：

13. **shareToken 停发时点（用户裁决：P3 上线即停发）**：`signRequest` 自 P3 后端上线即停写 `share_token`，不设配置开关。已知并接受代价：后端上线至新版小程序放量间的空窗内，新产生的待签记录旧版客户无法经匿名链接签署（存量已签发 token 的待签记录不受影响）；缓解=小程序先提审、后端部署后随即发布强更（§17），把空窗压到小时级。窗口期存量匿名签字按 §11/§19-20 留痕。
13a. **混合角色行排除**：生效角色含任一内部角色的员工行不归档、不迁移（防停用真实员工），`MIXED_SKIPPED` issue 留痕；仅纯客户行迁出（§7）。
14. **P1 临时口径保留至清零**：拦截器 `isCustomer(roles)` 门禁与员工/组织/统计的 role 排除谓词，在「纯客户员工行清零」前保留（保护 DEDUP_CONFLICT/NO_ANCHOR 空窗），清零断言后独立小提交移除；蓝图「P3 生效后不留双口径」顺延至清零时点（§7/§8.2）。
15. **迁移裁定机制**：`client_migration_issue.decision_json` + resolve 端点 `decision` 字段（口令/openid 取源、补锚点），Runner 重跑消费；`MANUAL_DONE`=带外完成永久跳过、`SKIPPED`=裁定不迁；全部经 API，不依赖生产库直写（§6.6）。
16. **在途工单防错归**：签退继承时工单快照 NULL → 先查过户嫌疑集，命中置 NULL + TRANSFER_SUSPECT issue，不回退当前归属（§5）。
17. **归档/PDF 快照化归 P2b**：P2a 客户侧 PDF 保持当前归属回退，防回填期客户可见回归（§5/§17）。
18. **客户电梯列表/详情沿用 `GET /elevators*`**（P1 既有契约 + `profileForUnit` 谓词 + 测试锁定），不建 `/client/elevators/{id}` 详情端点；蓝图 R3 例外面据此增补（§8.2）。
19. **客户记录读端点错误码统一 404**（不存在/跨租户/非最新/不可见状态不可区分），承接 C-25⑧ 治理方向；写端点门禁通过后保留 422（§9.1）。
20. **兼容期匿名签字留痕**：`actor_type=ANONYMOUS` + token 前 8 位 + 租户，快照列 NULL 不虚构签字人（§11）。
21. **P2a 交付最小管理端核对视图**（平台域管理门控；操作者=平台管理员/维保经理），不推迟到 P4、不依赖脚本直连（§6.6）。
22. **迁移编号修正（writing-plans 期发现）**：`client_migration_issue` 表与 `idx_record_unit/idx_fault_unit/idx_order_unit` 三索引由 V49 移至 **V47**——P2a 先于 P3 独立上线即依赖它们，而 Flyway 随 jar 全量执行、无法只挑 V47 不跑 V48/V49；V49 保留审计两列 + `idx_audit_unit` + `idx_emp_principal`。蓝图 §9.2「V49 含回填辅助表」相应顺延修正。
