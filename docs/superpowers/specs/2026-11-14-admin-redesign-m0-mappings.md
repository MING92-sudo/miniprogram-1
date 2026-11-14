# 管理端重构 M0 四张对照表（角色 / 权限码 / 视图 / 状态机）

**日期**：2026-11-14
**状态**：M0 出口交付物（草案，待用户评审；含 5 项待裁决人事/兼容决策与 4 项现存缺陷登记）
**上位文件**：`docs/superpowers/specs/2026-11-14-admin-redesign-design.md`（程序级总体设计 V1.0，commit 373ae99）§9「M0 出口四张对照表」
**用途**：M1（后端权限底座）启动前的唯一迁移事实源。本文件定稿前，M1 数据迁移脚本不得执行。

---

## 0. 阅读指引与证据基线

### 0.1 四张表的关系

```
表1 角色映射 ──决定──> 表2 权限码映射（角色×码）──决定──> 表3 视图映射（码×视图×操作）
                                                          │
表4 状态机对照 ──约束──> 表3 中"操作触发哪个状态转移" ──────┘
```

- 表 1/2 是 M1 后端迁移的输入；表 3 是 M2—M5 视图迁移的输入；表 4 是 M4 转移测试与合规回归的输入。
- 所有"新"侧取值以 `C:\Users\18223\Desktop\UI` 三份设计文档为准（M0 归档至 `docs/16-梯维通设计输入/`）；所有"旧"侧取值以本仓库代码实测为准，**实测优先于规范**（AGENTS §0）。

### 0.2 证据基线（旧侧，逐条可复核）

| 事实 | 证据位置 |
|---|---|
| 12 旧角色码 + 部门 + adminConsole | `backend/.../auth/RoleCatalog.java` |
| 24 旧能力码 + 逐角色授权 | `backend/.../auth/PermissionMatrix.java`（CAPABILITIES + grants） |
| 种子账号（6 个，密码均 123456 BCrypt，SEED_DEMO_DATA 门控、仅空库） | `backend/.../service/DemoDataSeeder.java` |
| 移交状态机 + 节点签字角色 | `backend/.../service/HandoverService.java`（init/submitDocs/submitInspect/recheckRectify/confirm/signNode，NODE_ROLES L67-71） |
| 急修单派单 OPEN→ASSIGNED + BWJX 编号 | `backend/.../service/DirectoryService.java` L194-222 |
| 维保计划池状态 UNASSIGNED/ASSIGNED/POSTPONE_PENDING/DISPATCHED/CANCELLED | `backend/.../entity/MaintainPlan.java` L19、`AdminScheduleService.java` |
| 工单状态 PENDING/PROCESSING/DONE；维保类别 FM/HM/TM/SM/OY；上报状态 8 态；确认 PENDING/CONFIRMED；急修 OPEN/CLOSED（前端文案缺 ASSIGNED） | `constants/index.js` L21-77、L334-343 |
| 21 旧视图路由 + meta.perm | `admin/src/router/index.js` |
| 使用单位账号挂在 use_unit（非 sys_employee） | `backend/.../db/migration/V34__use_unit_account.sql` |

### 0.3 待裁决登记（M1 前置，代码不猜）

| # | 待裁决项 | 影响表 | 处置 |
|---|---|---|---|
| P-1 | **INST_MGR 主管人选**：安装部新增主管层级，存量安装账号的上级 | 表1 | 用户指定；未决前迁移脚本 INST_MGR 段不执行 |
| P-2 | **LEADER→MAINT_SUP 项目部归属**：班组长升级为项目部主管，需补"项目部"组织节点并指定每人归属 | 表1/表4 | 用户指定；组织树项目部节点先建空壳，归属待填 |
| P-3 | **CLIENT_SIGNER 兼任授予名单**：哪些使用单位管理员兼任签字人 | 表1/表2 | 用户指定；默认不自动授予 |
| P-4 | **存量单人作业记录的双签兼容**：旧单人记录（无配合人）vs 新强制双签核验三条件 | 表4 | 建议：存量记录按"单人豁免"标记归档，不追溯补签；新记录强制双签。待用户确认 |
| P-5 | **修订 v2 是否需重报 2.6**：归档后修订生成新版本是否触发监管上报 | 表4 | 已列 docs/06 待确认；**确认前修订只生成内部新版本，不触发任何上报**（AGENTS §2.4 无自动重试红线） |

### 0.4 现存缺陷登记（本次只登记，不在 M0 修复）

| # | 缺陷 | 证据 | 风险 | 建议落点 |
|---|---|---|---|---|
| D-1 | `HandoverService.NODE_ROLES` 的 `INSTALLER_HANDOVER` 节点要求角色 `"INSTALLER"`，该码已不在 RoleCatalog（现为 INSTALL_MANAGER/INSTALL_CLERK/INSTALL_WORKER）→ **无任何账号能签该节点** | HandoverService.java L67-71 | 移交安装方签字链断裂 | M4 移交状态机重排时修正为 INST_PM/INST_CLERK |
| D-2 | `MAINTENANCE_CONFIRM` 节点允许 LEADER 签字，但 docs/14 §10 用例 8 要求 confirmed_by 必须是维保经理 | HandoverService.java NODE_ROLES vs docs/14 | 归档确认越权 | M4 收敛为 MAINT_MGR（PLATFORM_ADMIN 审计） |
| D-3 | 小程序 `UNIT_FAULT_STATUS_TEXT` 只有 OPEN/CLOSED，缺 ASSIGNED → 已派单急修单在门户显示为"处理中" | constants/index.js L334-337 vs DirectoryService L209 | 门户口径失真（非安全） | M5 小程序对齐时补 ASSIGNED 文案 |
| D-4 | 管理端 `FaultsView` 派单按钮无前端权限门控（后端已强校验） | admin FaultsView 派单按钮 | 体验问题，非越权（后端兜底） | M3/M4 重写时按 PermButton 两档门控补齐 |

---

## 表 1 · 角色映射与账号级迁移

### 1.1 角色码映射总表（12 旧 → 13 目标）

> 目标 = 9 新角色 + 3 扩展角色 + SYS_ADMIN 保留；ASSISTANT 并入 MAINT_TECH 不再单独建号。
> 部门列：PLATFORM 平台 / INSTALL 安装部 / MAINT 维保部 / UNIT 使用单位（docs/14 三平级部门 + 平台）。

| 旧码 | 旧部门 | 目标码 | 新部门 | 层级/语义变化 | adminConsole（Web 可登录） | 迁移方式 |
|---|---|---|---|---|---|---|
| `ADMIN` | PLATFORM | `PLATFORM_ADMIN` | PLATFORM | 语义一致：运维全量、不代签、敏感操作二次确认 | 是 | 码值改名 + 兼容层翻译 |
| `SYS_ADMIN` | PLATFORM | `SYS_ADMIN` | PLATFORM | 保留不启用，与 PLATFORM_ADMIN 同权 | 是 | 不变 |
| `INSTALL_MANAGER` | INSTALL | `INST_PM` | INSTALL | 安装部项目负责人 | 是（移交为主，兼容保留） | 码值改名 |
| `INSTALL_CLERK` | INSTALL | `INST_CLERK` | INSTALL | 安装部资料员（仅资料与整改证据） | 是（移交为主，兼容保留） | 码值改名 |
| `INSTALL_WORKER` | INSTALL | `INSTALL_WORKER`（扩展） | INSTALL | 新设计无安装员；保留现场查验/整改照片职能 | 否（小程序） | 不变 + 补扩展码 |
| —（新增） | — | `INST_MGR` | INSTALL | 安装部主管，INST_PM 新上级 | 是 | **新建 + P-1 人事指定** |
| `MAINT_MANAGER` | MAINT | `MAINT_MGR` | MAINT | 维保部经理：合同/跨项目调度/修订审批 | 是 | 码值改名 |
| `LEADER` | MAINT | `MAINT_SUP` | MAINT | **语义升级**：班组长→项目部主管；班组保留为 TEAM 范围 | 是 | 码值改名 + **P-2 项目部归属** |
| `WORKER` | MAINT | `MAINT_TECH` | MAINT | 维保人员（主检/配合人均为 MAINT_TECH 账号） | 否（小程序） | 码值改名 |
| `ASSISTANT` | MAINT | 并入 `MAINT_TECH` | MAINT | 配合人=MAINT_TECH 账号 + 工单角色属性 | 否 | **账号合并**（见 1.2） |
| `MAINT_BIZ` | MAINT | `MAINT_BIZ`（扩展） | MAINT | 商务：合同/客户域写；不见现场照片/定位/签字（R4） | 是 | 不变 + 补扩展码 |
| `MAINT_QUALITY` | MAINT | `MAINT_QUALITY`（扩展） | MAINT | 质量：跨班组只读 + 抽检写；不得签退/重报/改工单（R3） | 是 | 不变 + 补扩展码 |
| `UNIT_ADMIN` | UNIT | `CLIENT_ADMIN` | UNIT | use_unit 账号，组织单级 | 是（**M5 新能力**） | 码值改名 |
| —（新增） | — | `CLIENT_SIGNER` | UNIT | use_unit 账号；管理员可兼任，签字按签字人身份独立留痕 | 小程序为主 | **新建 + P-3 兼任名单** |

**码值迁移原则**（spec §4.1）：
1. 角色码字符串变更经**兼容层映射**——旧码注解（`@RequirePerm`/`RoleCatalog` 引用）与旧 token 的 role claim 在兼容期内自动翻译到新码；兼容期内旧端点行为逐条不变（兼容性测试证明）。
2. `sys_employee.role` 单列过渡为 `sys_user_role` 多行（多对多）；过渡期单列保留只读，写路径走多行表。
3. 未知角色/未知码一律拒绝（fail-closed，沿用 PermissionMatrix 初始化校验：`grants.keySet == CAPABILITIES`）。
4. 多角色仅限**同部门内**兼任（docs/14 R7，偏离 D-A2）；跨部门兼任禁止。

### 1.2 账号级迁移

#### 1.2.1 种子账号（DemoDataSeeder，仅空库 + SEED_DEMO_DATA）

| 账号 | 姓名 | 手机号 | 旧角色 | 目标角色 | 组织归属 | 迁移备注 |
|---|---|---|---|---|---|---|
| emp_1 | 张伟 | 13800000001 | WORKER | MAINT_TECH | 维保一班（TEAM） | PID=6901282369105174537；主检/配合人属性按工单 |
| emp_2 | 陈刚 | 13800000002 | LEADER | MAINT_SUP | 维保一班→**项目部（P-2 待填）** | PID=990002；升级为项目部主管 |
| emp_3 | 李强 | 13800000004 | WORKER | MAINT_TECH | 维保一班（TEAM） | PID=6901284774286860288 |
| unit_1 | 王芳 | 13800000003 | UNIT_ADMIN | CLIENT_ADMIN | 重庆世纪物业（uu_1，ORG_FLAT） | use_unit 账号；CLIENT_SIGNER 兼任待 P-3 |
| emp_admin | 郑浩 | 13800000009 | ADMIN | PLATFORM_ADMIN | 平台（ALL 运维只读） | — |
| emp_sys | 系统管理员 | 13800000010 | SYS_ADMIN | SYS_ADMIN | 平台 | 保留不启用 |

> 种子库**无** ASSISTANT / INSTALL_* / MAINT_BIZ / MAINT_QUALITY 账号；这些角色的迁移规则见 1.2.2，生产账号按同规则批量回填。
> use_unit uu_2 重庆蓝湾物业（吴静）未开通账号（account=NULL），迁移后仍为无账号单位节点，不受影响（V34 唯一索引允许多 NULL）。

#### 1.2.2 ASSISTANT 账号合并规则（D-A6）

1. 存量 `role=ASSISTANT` 的 sys_employee 账号 → `role=MAINT_TECH`，部门不变（MAINT）。
2. 该账号在历史工单中的"配合人"身份由 `work_order.assistant_id` 字段保留（V23 已落 assistant_signature_file_id/at），**不新建账号、不改历史签字归属**。
3. 合并后若与某 WORKER 账号为同一自然人（同手机号），按 docs/14 手机号互斥规则去重：保留一个 MAINT_TECH 账号，另一账号停用（enabled=false）并转交其名下工单（沿用 R8 离职转交）。
4. 新工单配合人 = 独立 MAINT_TECH 账号 + 工单角色属性，与"各签各的"（V23）一致。

#### 1.2.3 生产迁移脚本骨架（Flyway V35+ 数据迁移，只追加）

> 以下为**骨架**，P-1/P-2/P-3 未决前对应段以注释占位，不得执行。实际脚本在 M1 spec→plan 中细化并补单测。

```sql
-- V35__role_model_v2_data.sql（示意，M1 细化）
-- 步骤 1：码值改名（单列过渡，兼容层同时生效）
UPDATE sys_employee SET role='PLATFORM_ADMIN' WHERE role='ADMIN';
UPDATE sys_employee SET role='INST_PM'        WHERE role='INSTALL_MANAGER';
UPDATE sys_employee SET role='INST_CLERK'     WHERE role='INSTALL_CLERK';
UPDATE sys_employee SET role='MAINT_MGR'      WHERE role='MAINT_MANAGER';
UPDATE sys_employee SET role='MAINT_SUP'      WHERE role='LEADER';
UPDATE sys_employee SET role='MAINT_TECH'     WHERE role IN ('WORKER','ASSISTANT');
UPDATE use_unit     SET role='CLIENT_ADMIN'   WHERE role='UNIT_ADMIN'; -- 若 use_unit 无 role 列则按账号体系映射

-- 步骤 2：sys_user_role 多行回填（同部门多角色）
INSERT INTO sys_user_role(user_id,user_type,role_code,scope_ref,version)
SELECT id,'EMPLOYEE',role,NULL,1 FROM sys_employee WHERE enabled=1;
-- use_unit 账号：user_type='UNIT'，role='CLIENT_ADMIN'

-- 步骤 3：INST_MGR 新建（P-1 待裁决，占位不执行）
-- INSERT INTO sys_employee(... role='INST_MGR' ...) VALUES (<人事指定>);

-- 步骤 4：MAINT_SUP 项目部归属（P-2 待裁决，占位不执行）
-- UPDATE sys_user_role SET scope_ref=<项目部org_id> WHERE role_code='MAINT_SUP';

-- 步骤 5：CLIENT_SIGNER 兼任（P-3 待裁决，占位不执行）
-- INSERT INTO sys_user_role(user_id,user_type,role_code) VALUES (<unit_id>,'UNIT','CLIENT_SIGNER');
```

### 1.3 执行约束

- 迁移失败自愈沿用 AGENTS V3.1（删 flyway_schema_history 失败行重跑）；打包必须 `mvn clean package`（V3.2 护栏，MigrationIntegrityTest）。
- 旧表旧列不删不改（spec §1 核心原则）；`sys_employee.role` 单列保留只读至兼容期结束。
- 322 单测不回退；迁移脚本须补"旧端点行为不变"兼容性测试 + 越权用例逐条留证（M1 门禁）。

---

## 表 2 · 权限码映射（24 旧 → 71+ 新）

### 2.0 ⚠ 关键语义冲突警示（迁移最大陷阱）

**旧 `order:*` 与新 `order:*` 指代不同实体，禁止按字面同名映射：**

| 域 | 旧系统含义 | 新系统含义 |
|---|---|---|
| `order:*` | **维保工单 WorkOrder**（签到/清单/签退，PENDING/PROCESSING/DONE） | **急修/报修 Fault**（7 态时间轴、SLA、抢单、代报修） |
| `fault:*` | **急修单 Fault**（OPEN/ASSIGNED/CLOSED） | （新设计无独立 fault 域，并入 order:*） |
| 维保作业 | order:read/write + record:read + plan:write | **plan:* / task:* / checklist:* / record:*** |

**正确映射方向**：
- 旧 `order:read/write`（维保工单）→ 新 `plan:view` / `task:accept` / `checklist:fill` / `record:view` / `record:submit` / `record:sign_tech`。
- 旧 `fault:read/write`（急修单）→ 新 `order:view_pool` / `order:view_own` / `order:create` / `order:dispatch` / `order:arrive` / `order:process` / `order:submit` / `order:confirm` / `order:close` / `order:cancel`。

> 此冲突同时影响表 3（OrderListView 是维保+救援混合列表，见 3.0）与表 4（工单状态机）。任何把旧 order:* 直接当作新 order:* 的实现都会把维保作业错误地塞进急修 7 态机，属 Blocker 级语义错误。

### 2.1 24 旧码逐码映射

> 三态：Y 可操作 / C 只读或需审批 / - 禁止。"授权差异"列标注新旧角色集变化；范围列标注数据范围收敛/扩展。
> 新码逐角色完整授权以 roles-permissions.md §4 为准，本表只列迁移对应与差异。

| # | 旧码 | 旧授权角色 | 新码（主映射） | 授权差异 / 范围变化 | 兼容期处理 |
|---|---|---|---|---|---|
| 1 | `console:read` | ADMIN,SA,IM,IC,MM,MB,LD,MQ | 工作台登录态（无独立码，dashboard 按角色聚合） | 新设计工作台=登录即可见，按范围聚合 KPI；旧"控制台读"语义被各域 view 码吸收 | 旧码翻译为"持有任一域 view 码" |
| 2 | `console:write` | ADMIN,SA | `config:manage`（平台）+ 各域 write | 旧"控制台写"=平台运维写；新拆为 config:manage + 域写码 | 翻译为 config:manage |
| 3 | `order:read` | ADMIN,SA,MM,LD,MQ,WK,AS,UA | `plan:view`+`record:view`+`task`读 | **语义=维保工单**（见 2.0）；WK/AS→MT 小程序侧；UA→CA 仅 record:view△ | 翻译为 plan:view∥record:view |
| 4 | `order:write` | ADMIN,SA,MM,LD,WK,AS | `task:accept`+`checklist:fill`+`record:submit`+`record:sign_tech` | **语义=维保作业写**；MM/LD 不直接作业（新设计作业=MT），管理写转 record:review/plan:generate | 按操作拆分翻译 |
| 5 | `fault:read` | ADMIN,SA,MM,LD,MQ,WK,AS,UA | `order:view_pool`+`order:view_own` | **语义=急修单**；UA→CA order:view_own（ORG_FLAT） | 翻译为 order:view_pool∥view_own |
| 6 | `fault:write` | ADMIN,SA,MM,LD,WK,AS,IW,UA | `order:create`/`dispatch`/`arrive`/`process`/`submit`/`confirm`/`close`/`cancel` | IW 旧有 fault:write→新 INSTALL_WORKER 无 order 域（仅 handover），**收敛**；UA→CA order:confirm/client:report | 按操作拆分；IW 段移除 |
| 7 | `record:read` | ADMIN,SA,MM,LD,MQ,WK,AS,UA | `record:view` | MQ 跨班组只读（ORG）；UA→CA **无 record:view**（新设计⑫records 隐藏 CA），门户走 client:download | CA 段移除，改 client:download |
| 8 | `report:retry` | ADMIN,SA,MM | `report:retry`（**扩展码保留**） | 不变；逐条人工重报，禁批量（AGENTS §2.4） | 扩展码原样保留 |
| 9 | `plan:write` | ADMIN,SA,MM,LD | `plan:generate`+`task:assign_partner` | **D-A1**：plan:generate 落地为手动派单/延期，不做批量生成；LD→MS | 翻译为 plan:generate（手动语义） |
| 10 | `template:write` | ADMIN,SA,MM,MQ | `template:write`+`template:toggle`（**扩展码**） | MQ 获 template:toggle（启停）；检查项模板区别于系统配置签字模板 | 扩展码保留 |
| 11 | `ledger:read` | ADMIN,SA,MM,IM,MB,LD,MQ,UA | `elevator:view`+`project:view` | IM→IP elevator:view△；UA→CA elevator:view△（ORG_FLAT） | 翻译为 elevator:view |
| 12 | `ledger:write` | ADMIN,SA,MM | `elevator:edit`+`elevator:transfer` | 新增双人确认（移装/报废）；关键字段编辑需审核 | 翻译为 elevator:edit |
| 13 | `employee:read` | ADMIN,SA,IM,IC,MM,LD | `user:view` | **收敛**：IP/IC 目标态失去 user:view（新设计安装部仅 INST_MGR 有 user:view△）；兼容期保留 | 兼容期翻译 user:view，目标态移除 IP/IC |
| 14 | `employee:write` | ADMIN,SA,IM,MM | `user:create`+`user:disable`+`user:reset_password`（扩展） | IM→IP 目标态收敛（账号管理归 INST_MGR/平台）；新增 reset_password 扩展码 | 兼容期保留，目标态收敛 |
| 15 | `contract:read` | ADMIN,SA,IM,MM,MB,UA | `contract:view` | IM→IP **无 contract:view**（新设计安装部无合同页）；UA→CA contract:view△（金额脱敏）；MB 扩展保留 | IP 段移除；金额服务端裁剪 |
| 16 | `contract:write` | ADMIN,SA,MB | `contract:edit` | MB 扩展获 contract:edit；MM 新增 contract:edit | 翻译为 contract:edit |
| 17 | `useunit:read` | ADMIN,SA,MM,MB,UA | `org`（单位节点）+`client:elevator_view` | 使用单位=组织单级节点；UA→CA 仅本单位 | 翻译为 org 节点读 |
| 18 | `useunit:write` | ADMIN,SA,MB | `client:edit`（**扩展码**） | MB 扩展获 client:edit（使用单位档案写）；平台 org 编辑 | 扩展码新增 |
| 19 | `handover:read` | ADMIN,SA,IM,IC,MM,MQ | `handover:view` | IM→IP△、IC△、MM✔、MQ△；新增 INST_MGR✔、CA△（生效后） | 翻译为 handover:view |
| 20 | `handover:write` | ADMIN,SA,IM,IC,IW,MM | `handover:create`/`edit`/`submit`/`doc_upload`/`doc_delete`/`review`/`receive`/`inspect`/`rectify`/`sign_*`/`void` | **按操作细分 16 码**；IW→handover:inspect/rectify（扩展）；D-1/D-2 节点角色修正 | 按操作拆分翻译 |
| 21 | `filing:read` | ADMIN,SA,IM,IC,MM,MQ | `filing:view`（**扩展码**） | 外接建档保留；MQ△ | 扩展码新增 |
| 22 | `filing:write` | ADMIN,SA,IM,IC,MM | `filing:write`（**扩展码**） | 不变 | 扩展码新增 |
| 23 | `region:write` | ADMIN,SA | `region:view`+`region:write`（**扩展码**） | 区域主数据保留；仅平台 | 扩展码新增 |
| 24 | `account:manage` | ADMIN,SA,MM | `user:create`/`user:disable`/`invite:manage` | 账号管理+邀请码；MM 获 invite:manage | 拆分翻译 |

### 2.2 扩展码定义（新设计 71 码之外，合规保留 + 扩展角色补码）

> 命名沿用 `<domain>:<action>`；扩展码与 71 新码同矩阵管理，fail-closed。

| 扩展码 | 域 | 授权角色（Y/C） | 用途 | 来源 |
|---|---|---|---|---|
| `report:view` | 监管 | PA✔ SA✔ MM✔ MS△ MQ△ | 上报异常清单/上报日志读 | 合规保留（旧 record:read 派生） |
| `report:retry` | 监管 | PA✔ SA✔ MM✔ | 逐条人工重报（禁批量） | 旧码原样保留 |
| `filing:view` | 监管 | PA✔ SA✔ IP✔ IC✔ MM✔ MQ△ IM△ | 外接建档读 | 合规保留 |
| `filing:write` | 监管 | PA✔ SA✔ IP✔ IC✔ MM✔ | 外接建档写（合同前置/缺件待补办/接管查验责任归属必填） | 合规保留 |
| `region:view` | 监管 | PA✔ SA✔ | 区域主数据读 | 合规保留 |
| `region:write` | 监管 | PA✔ SA✔ | 区域主数据写 | 旧码原样保留 |
| `template:view` | 监管 | PA✔ SA✔ MM✔ MQ△ | 检查项模板读 | 合规保留 |
| `template:write` | 监管 | PA✔ SA✔ MM✔ MQ✔ | 检查项模板写 | 旧码原样保留 |
| `template:toggle` | 监管 | PA✔ SA✔ MQ✔ | 检查项模板启停 | MQ 扩展补码 |
| `platform:worker_view` | 监管 | PA✔ SA✔ IP△ IC△ MM△ MS△ | 平台登记人员读（2.3/2.4/2.5） | 合规保留（旧 employee:read 派生） |
| `platform:worker_register` | 监管 | PA✔ SA✔ MM✔ | 平台登记人员写（contractFile/certificateFile multipart） | 合规保留 |
| `platform:sync` | 监管 | PA✔ SA✔ | 平台同步触发（只读探针/手动同步，无自动重试） | 合规保留 |
| `record:spotcheck` | 维保 | MQ✔ | 抽查标记/抽检写（比例读 sys_config） | MQ 扩展补码 |
| `client:edit` | 客户 | PA✔ MB✔ | 使用单位档案写 | MB 扩展补码（旧 useunit:write） |
| `user:reset_password` | 组织 | PA✔ SA✔ MM✔ IM✔ | 重置账号口令 | 旧 employee:write 派生 |
| `handover:inspect`（IW 扩展授权） | 移交 | IW✔（仅被指派） | 安装员现场查验 | IW 扩展（新设计原授 MT） |
| `handover:rectify`（IW 扩展授权） | 移交 | IW✔ | 安装员整改照片 | IW 扩展（新设计原授 IP/IC） |

### 2.3 每角色导航可见性推导

**推导规则**：视图可见 = 页面级码 ≠ `-`（Y 可操作 / C 只读徽标 / 运=运维只读）；直达无权路由→403。页面级码取自 admin-spec §3。

**Web 可登录角色**（10）：PA、SA、MM、MS、IM、IP、IC、MB、MQ、CA（M5 新能力）。
**小程序-only 角色**（3）：MT、IW、CS（adminConsole=false，Web 导航不适用）。

#### 2.3.1 五个主视角（admin-spec §3 逐字 reproduce，作为权威基准）

| 视图组 / 视图 | PLATFORM_ADMIN | MAINT_MGR | MAINT_SUP | INST_MGR | CLIENT_ADMIN |
|---|---|---|---|---|---|
| 概览 · dashboard | ✔ | ✔ | ✔ | ✔ | ✔ |
| 组织与权限 · org | ✔ | C | C | C | C（单级） |
| · users | ✔ | C | C | C | C |
| · roles | ✔ | ✕ | ✕ | ✕ | C |
| · grants | ✔ | ✔ | ✔ | ✔ | ✔ |
| 台账中心 · projects | ✔ | ✔ | ✔ | C | C |
| · contracts | ✔ | ✔ | C | ✕ | C（脱敏） |
| · elevators | C | ✔ | ✔ | C | C |
| 安装移交 · handovers | ✔ | ✔ | ✔ | ✔ | C（生效后） |
| 维保作业 · plans | 运 | ✔ | ✔ | ✕ | C |
| · records | ✔ | ✔ | ✔ | ✕ | ✕ |
| 急修报修 · dispatch | 运 | ✔ | ✔ | ✕ | C（own） |
| · order/:id | 运 | ✔ | ✔ | ✕ | C（own） |
| 数据与系统 · stats | ✔ | ✔ | ✔ | ✔ | ✔ |
| · audit | ✔ | ✕ | ✕ | ✕ | C |
| · settings | ✔ | ✕ | ✕ | ✕ | ✕ |
| 监管与档案（6 视图，D-A4） | ✔ | 部分 | 部分 | 部分 | ✕ |

> 监管与档案组逐角色细分见 2.3.3。"运"=D3 平台运维只读（plans/dispatch/order 平台只看不派）。

#### 2.3.2 扩展/其他 Web 角色推导（按持有页面码）

| 视图 | INST_PM(IP) | INST_CLERK(IC) | MAINT_BIZ(MB) | MAINT_QUALITY(MQ) | SYS_ADMIN(SA) |
|---|---|---|---|---|---|
| dashboard | ✔ | ✔ | ✔ | ✔ | ✔（=PA） |
| org | ✕ | ✕ | C（单位节点） | ✕ | ✔ |
| users | ✕ | ✕ | ✕ | ✕ | ✔ |
| roles | ✕ | ✕ | ✕ | ✕ | ✔ |
| grants | ✕ | ✕ | ✕ | ✕ | ✔ |
| projects | C | ✕（台账入口隐藏，决策10） | ✕ | ✕ | ✔ |
| contracts | ✕ | ✕ | ✔ | ✕ | ✔ |
| elevators | C | C | C | C | ✔ |
| handovers | ✔（△） | ✔（△） | ✕ | C | ✔ |
| plans | ✕ | ✕ | ✕ | ✕ | 运 |
| records | ✕ | ✕ | ✕（R4 不见现场） | C（跨班组只读） | ✔ |
| dispatch / order | ✕ | ✕ | ✕ | C（只读，无派单写） | 运 |
| stats | ✕ | ✕ | C | ✕ | ✔ |
| audit | ✕ | ✕ | ✕ | ✕ | ✔ |
| settings | ✕ | ✕ | ✕ | ✕ | ✔ |
| 监管与档案 | filing✔ | filing✔ | ✕ | filing△/template✔ | ✔ |

#### 2.3.3 监管与档案组（6 视图）逐角色页面码

| 视图 | 页面码 | PA/SA | MM | MS | MQ | IP/IC | IM | MB | CA |
|---|---|---|---|---|---|---|---|---|---|
| 上报异常清单 | report:view | ✔ | ✔ | C | C | ✕ | ✕ | ✕ | ✕ |
| 上报日志 | report:view | ✔ | ✔ | C | C | ✕ | ✕ | ✕ | ✕ |
| 外接建档 | filing:view | ✔ | ✔ | ✕ | C | ✔ | C | ✕ | ✕ |
| 区域管理 | region:view | ✔ | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ | ✕ |
| 检查项模板 | template:view | ✔ | ✔ | ✕ | C（+toggle✔） | ✕ | ✕ | ✕ | ✕ |
| 平台登记人员 | platform:worker_view | ✔ | C | C | ✕ | C | ✕ | ✕ | ✕ |

> 重报动作 `report:retry` 仅 PA/SA/MM（Y），MS/MQ 只读不可重报（R3）。区域管理仅平台（忠实旧 region:write 页面门控）。

#### 2.3.4 小程序-only 角色（Web 不适用，菜单走 /me/menus）

| 角色 | 小程序工作台 | 关键码 |
|---|---|---|
| MAINT_TECH(MT) | 维保作业（本人派工 SELF + 班组共享池 TEAM） | task:accept/checklist:fill/record:submit/record:sign_tech/order:view_own/arrive/process |
| INSTALL_WORKER(IW) | 安装移交（现场查验/整改照片） | handover:inspect/rectify（扩展） |
| CLIENT_SIGNER(CS) | 使用单位签署（待签合并） | client:sign/record:view△/order:view_own |

---

## 表 3 · 视图字段/操作级映射（21 旧视图 → 23 新视图）

### 3.0 ⚠ 细化说明与混合域拆分警示

spec §6.2 的"OrderListView + FaultsView → #/dispatch 合并"是**简化表述**，需按实体域拆分：

- **OrderListView / OrderDetailView 是维保工单（WorkOrder）视图**，其 workType 横跨维保（HM/TM/SM/OY）与救援（FM 按需/困人救援）。
  - 维保类工单 → 新 `#/plans`（计划/工单列表）+ `#/records`（记录单复核/详情）。
  - 救援类（FM）工单 → 新 `#/dispatch` + `#/order/:id`（急修 7 态）。
- **FaultsView 是急修单（Fault）视图** → 新 `#/dispatch` + `#/order/:id`。
- **禁止**把维保工单塞进急修 7 态机（见 2.0 / 表 4）。新 `#/order/:id` 的 7 态时间轴只服务急修/报修。

> 本警示已同步修订 spec §6.2（V1.1）。

### 3.1 视图旧→新总表

| 旧视图 | 旧 meta.perm | 新落点 | 新页面码 | 迁移期 |
|---|---|---|---|---|
| LoginView | — | #/login | 登录态 | M2 |
| ForbiddenView | — | 403 | — | M2 |
| NotFoundView | — | 404 | — | M2 |
| DashboardView | console:read | #/dashboard | 登录态 | M2 |
| OrderListView | order:read | #/plans（维保）+ #/dispatch（FM 救援） | plan:view / order:view_pool | M4 |
| OrderDetailView | order:read | #/records 详情（维保）+ #/order/:id（FM） | record:view / order:view_own | M4 |
| FaultsView | fault:read | #/dispatch + #/order/:id | order:view_pool∥view_own | M4 |
| ScheduleView | plan:write | #/plans | plan:view（+generate 手动） | M4 |
| LedgerView | ledger:read | #/elevators（合并） | elevator:view | M3 |
| ElevatorsView | ledger:read | #/elevators（合并） | elevator:view | M3 |
| HandoverView | handover:read | #/handovers | handover:view | M4 |
| ContractView | contract:read | #/contracts | contract:view | M3 |
| EmployeesView | employee:read | #/users | user:view | M3 |
| CompanyView | employee:read | #/org（维保单位节点） | user:view | M3 |
| UseUnitsView | useunit:read | #/org（使用单位节点） | user:view | M3 |
| RegisterWorkersView | employee:read | 监管·平台登记人员 | platform:worker_view | M4 |
| FailedRecordsView | order:read | 监管·上报异常清单 | report:view | M4 |
| UploadLogsView | record:read | 监管·上报日志 | report:view | M4 |
| FilingView | filing:read | 监管·外接建档 | filing:view | M4 |
| RegionView | region:write | 监管·区域管理 | region:view | M4 |
| TemplateManageView | template:write | 监管·检查项模板 | template:view | M4 |
| —（新增） | — | #/org 树 / #/roles / #/grants / #/projects / #/records / #/stats / #/audit / #/settings | 见 2.3 | M3/M5 |

### 3.2 逐视图操作级映射（每个按钮的新落点 + 权限码）

> 门控列：Y=原生可点 / C=只读或需审批 / disabled=永久缺权原生禁用 / aria=临时前置未满足（两档门控 §4.3）。
> "旧门控"标注旧实现是否有前端 can() 校验（D-4 类缺陷登记）。

#### 3.2.1 台账域（M3）

**ElevatorsView + LedgerView → #/elevators**

| 旧操作 | 旧门控 | 新落点 | 新权限码 | 备注 |
|---|---|---|---|---|
| 新建电梯 | canWrite(ledger:write) | #/elevators 新建抽屉 | elevator:edit | 关键字段编辑需审核 |
| 编辑 | canWrite | 编辑抽屉 | elevator:edit | 同上 |
| 停用/启用 | canWrite | 状态切换 | elevator:edit | 移装/停用/报废走双人确认 |
| 删除 | canWrite | 删除（二次确认） | elevator:edit | 危险变体 Modal |
| 批量分配 | canWrite | 批量操作条 | elevator:edit | 行多选 |
| 批量经纬度 | canWrite | 批量操作条 | elevator:edit | |
| 平台回填 | canWrite | 平台回填 | platform:sync | 2.7 按 factoryNumber/registrationCode/deviceCode（AGENTS §5.5） |
| 二维码 | 无门控 | 设备码打印 | elevator:qr | 补门控 |
| 上传年检报告（Ledger） | canWrite | 档案附件 | elevator:edit | 文件 ?s= 重签 |
| 预览/下载（Ledger） | 无门控 | 档案预览 | elevator:view | 补门控；导出按权限重生成 |
| —（新增） | — | 移装/报废双人确认 | elevator:transfer | 发起+确认必须不同账号（biz_elevator_change） |

**ContractView → #/contracts**

| 旧操作 | 旧门控 | 新落点 | 新权限码 | 备注 |
|---|---|---|---|---|
| 新建合同 | can(contract:write) | 新建抽屉 | contract:edit | MB 扩展可写 |
| 列表（只读） | contract:read | 列表 | contract:view | 金额分级脱敏（服务端裁剪） |
| —（新增） | — | 编辑 | contract:edit | 旧无编辑，新增 |
| —（新增） | — | 金额脱敏对照卡 | contract:view | 维保主管仅期与台量；安装部无页；经理/使用单位/平台完整 |

**EmployeesView → #/users**

| 旧操作 | 旧门控 | 新落点 | 新权限码 | 备注 |
|---|---|---|---|---|
| 新建 | canWrite(employee:write) | 开通账号 | user:create | 状态机 待激活→正常→停用/离职 |
| 编辑 | canWrite | 编辑 | user:create | |
| 重置密码 | canWrite | 重置口令 | user:reset_password | 扩展码 |
| 删除 | canWrite | 停用/离职转交 | user:disable | 不物理删，enabled=false + 会话清除（R8） |
| 班组 select | canWrite | 组织归属 | role:assign | 多角色同部门 |

**CompanyView / UseUnitsView → #/org**

| 旧操作 | 旧门控 | 新落点 | 新权限码 | 备注 |
|---|---|---|---|---|
| 编辑/保存（Company） | canWrite | 维保单位节点详情 | role:assign | entityId disabled 保留 |
| 新建（UseUnits） | canWrite | 使用单位单级节点 | role:assign | 应用层禁挂子节点 |
| 编辑（UseUnits） | canWrite | 单位档案 | client:edit | MB 扩展 |
| 开通账号（UseUnits） | canWrite | 开通门户账号 | user:create | account+initialPassword+inviteCode 一次性展示 |
| 删除（UseUnits） | canWrite | 停用节点 | user:disable | |

**新增 #/org 树 / #/roles / #/grants / #/projects**

| 新视图 | 关键操作 | 权限码 |
|---|---|---|
| #/org | 组织树 CRUD（公司→部门/项目部→班组；使用单位单级） | role:assign |
| #/roles | 角色权限矩阵（**只读**，写入口二期） | role:assign |
| #/grants | 邀请码（1—30 天默认 7）+ 限时授权（到期自动回收） | invite:manage |
| #/projects | 项目台账 | project:view / project:edit |

#### 3.2.2 作业域（M4）

**ScheduleView → #/plans**

| 旧操作 | 旧门控 | 新落点 | 新权限码 | 备注 |
|---|---|---|---|---|
| 生成排班池 | canWrite(plan:write) | **改为手动派单入口** | plan:generate | **D-A1**：不做批量生成 N 条；定时任务驱动不变（AGENTS §3.6） |
| 指派 | canWrite | 指派主检/配合 | task:assign_partner | |
| 立即派单 | canWrite | 手动派单 | plan:generate | 同项目同日到期一次性全部派单 |
| 申请延期 | canRead | 延期申请 | plan:generate | |
| 延期审批通过/驳回 | canWrite | 延期审批 | plan:generate | MaintainPlan POSTPONE_PENDING |
| —（新增） | — | 到期基线只读投影 | plan:view | TSG 到期基线，读接口不触发派单 |

**OrderListView（维保部分）→ #/plans；OrderDetailView（维保）→ #/records 详情**

| 旧操作 | 旧门控 | 新落点 | 新权限码 | 备注 |
|---|---|---|---|---|
| 到期筛选（today/soon/overdue） | order:read | #/plans 筛选 | plan:view | |
| 状态筛选 PENDING/PROCESSING/DONE | order:read | #/plans 状态 | plan:view | 维保工单状态，非急修 7 态 |
| 详情→/orders/:id | order:read | #/records 详情抽屉 | record:view | 检查项+双签+记录预览 |

**HandoverView → #/handovers**

| 旧操作 | 旧门控 | 新落点 | 新权限码 | 备注 |
|---|---|---|---|---|
| 确认归档 | can(handover:write) | 归档转移 | handover:sign_maint | **D-2**：收敛 MAINT_MGR；archive() 副作用保留（建档+首保+15天） |
| —（新增） | — | 10 态时间轴 | handover:view | 表 4.1 |
| —（新增） | — | 资料清单（6 类必传+哈希） | handover:doc_upload | 旧 3 类→新 6 类 |
| —（新增） | — | 容缺受理 | handover:review | |
| —（新增） | — | 接收指派 | handover:receive | |
| —（新增） | — | 作废三段链 | handover:void | 安装主管发起→维保经理确认→平台审计 |
| —（新增） | — | 知会使用单位 | handover:notify_client | |

**FaultsView → #/dispatch + #/order/:id**

| 旧操作 | 旧门控 | 新落点 | 新权限码 | 备注 |
|---|---|---|---|---|
| 筛选 OPEN/ASSIGNED/CLOSED | fault:read | #/dispatch 筛选 | order:view_pool | |
| 详情 | 无门控 | #/order/:id | order:view_own | 补门控 |
| 派单（OPEN-only） | **无门控（D-4）** | 派单/改派 | order:dispatch | 补 PermButton 两档门控 |
| —（新增） | — | SLA 倒计时/超时升级 | order:escalate | sla_minutes/deadline 创建时冻结 |
| —（新增） | — | 抢单 | order:grab | |
| —（新增） | — | 代报修表单 | order:create_proxy | proxy_source+recording_no `^REC-\d{4,6}$` |
| —（新增） | — | 批量派单 | order:dispatch | |
| —（新增） | — | 使用单位确认区 | order:confirm | CA client 域 |

**OrderDetailView（FM 救援部分）→ #/order/:id**：7 态时间轴 + 代报修 + 使用单位确认（同上）。

**新增 #/records（记录单复核）**

| 新操作 | 权限码 | 备注 |
|---|---|---|
| 双签三条件核验 | record:review | 主检签+配合签+时长≥30min |
| 退回 | record:review | |
| 抽查标 | record:spotcheck | MQ 扩展；比例读 sys_config |
| 归档后修订 v2+哈希 | record:amend | MM 发起+PA 确认；**不触发上报**（P-5） |

#### 3.2.3 数据与系统域（M5）

| 新视图 | 关键操作 | 权限码 | 备注 |
|---|---|---|---|
| #/stats | 7 指标随范围聚合 | stats:view | 分项之和=总数 |
| #/stats | 普通导出（脱敏） | stats:export | |
| #/stats | 敏感导出审批 | data:export_sensitive | 用途≥10字+知悉勾选+二次确认→审批中→平台审批+再确认→已完成 |
| #/audit | 审计日志（只读，无删除入口） | audit:view | 六类动作；sys_audit_log 只读不可删改 |
| #/settings | 系统配置（仅平台） | config:manage | SLA/保养周期/抽查比例/脱敏开关/签字模板；保存即时生效+写审计；环境级密钥仍走环境变量 |

#### 3.2.4 监管与档案域（M4，D-A4 合规保留）

| 旧视图 | 旧操作 | 新落点 | 新权限码 | 合规约束 |
|---|---|---|---|---|
| FailedRecordsView | 逐条人工重报 | 上报异常清单 | report:retry | **禁批量**（AGENTS §2.4）；仅 FAILED 逐条 |
| UploadLogsView | 筛选 SUCCESS/FAILED × UPLOAD/REUPLOAD/LEGACY × originalRecordId | 上报日志 | report:view | 脱敏落库；LEGACY 受 REG_LEGACY_UPLOAD_ENABLED 默认关闭 |
| FilingView | 外接建档（合同前置/缺件待补办/接管查验责任归属必填） | 外接建档 | filing:write | archive_source=2 + 首保+3日 |
| RegionView | 新建/编辑/删除（can region:write） | 区域管理 | region:write | 仅平台 |
| TemplateManageView | 检查项模板启停/编辑 | 检查项模板 | template:write/toggle | 区别于系统配置签字模板 |
| RegisterWorkersView | 平台登记人员（2.3/2.4 multipart） | 平台登记人员 | platform:worker_register | contractFile/certificateFile；openid 只服务端换取 |

#### 3.2.5 壳与通用（M2）

| 旧 | 新 | 备注 |
|---|---|---|
| LoginView（phone+password+redirect） | #/login（账号+密码，沿用现有后端认证） | 使用单位 Web 登录 M5 新能力 |
| ForbiddenView | 403（所需权限码+允许角色+当前角色） | |
| NotFoundView | 404 | |
| DashboardView（4 工单卡+3 平台卡+7日趋势+上报行） | #/dashboard（KPI 7 项+预警条+待办） | 在管项目/电梯、待审核移交、待复核、SLA 超时、合同临期≤90天、双签待补 |
| Element Plus 组件 | 自建组件库（DataTable/Drawer/Modal/Form/Toast/StatusTag/Timeline/PermButton） | D-A5；关闭 docs/12 D-05 包体 |
| 原型"设计规范/数据自检"页 | 开发/验收工具，不进生产导航 | D-A3 |

---

## 表 4 · 三套状态机对照（+ 维保计划池 / 外接建档）

### 4.1 安装移交状态机

**旧（HandoverService，6 态）**：INIT → DOC_SUBMITTING → INSPECTING →（RECTIFYING ⇄）→ PENDING_CONFIRM → ARCHIVED

**新（roles §6，10 态 / 11 转移）**：草稿 → 待安装部审核 →（退回整改 ⇄）→ 待维保接收 → 验收中 → 待双方签字 → 待维保签字 → 已生效 → 已归档；已作废（三段链）；容缺受理分支。

#### 4.1.1 逐态映射

| 旧态 | 旧文案 | 新态 | 转移差异 | 合规影响 |
|---|---|---|---|---|
| INIT | 待发起 | 草稿 | 一致 | 无 |
| DOC_SUBMITTING | 资料移交中 | 待安装部审核 | 旧 3 类必传文件→新 6 类必传+哈希；新增容缺受理分支 | 资料完整性卡点保留（3 强制项不变） |
| INSPECTING | 现场查验 | 验收中 | 旧 submitInspect 按 SAFETY_DEVICE/BRAKE FAIL 分流→新验收判定 | 现场查验责任归属必填保留 |
| RECTIFYING | 整改复验中 | 退回整改 | 旧 recheckRectify PASS→PENDING_CONFIRM；新逐条复验（docs/15 行为变更） | **任一不合格即整改 + 逐条复验**（docs/15 待实施） |
| PENDING_CONFIRM | 待确认归档 | 待双方签字 → 待维保签字 | 旧单步 confirm→新双签链（安装方签+维保方签） | **D-1**：INSTALLER_HANDOVER 节点角色失效需修正；**D-2**：MAINTENANCE_CONFIRM 收敛 MAINT_MGR |
| ARCHIVED | 已归档 | 已生效 → 已归档 | 旧 confirm+archive 单事务→新"已生效"触发 archive() 副作用 | **archive() 副作用必须保留**：建档 archiveSource=1 + 预创建 HM 首保工单+15天 + elevatorId 幂等 |
| —（新增） | — | 已作废 | 三段链：安装主管发起→维保经理确认→平台审计 | 作废不可逆，全程审计 |

#### 4.1.2 存量移交回填映射

```
INIT            → 草稿
DOC_SUBMITTING  → 待安装部审核
INSPECTING      → 验收中
RECTIFYING      → 退回整改
PENDING_CONFIRM → 待双方签字
ARCHIVED        → 已归档
```

> 节点签字角色修正（D-1/D-2）在 M4 转移测试中兜底；映射不确定的态标"待裁决"不硬改（spec §10 风险 2）。

### 4.2 维保记录单状态机

**旧（WorkOrder + MaintainRecord）**：
- 工单：PENDING（待执行）→ PROCESSING（进行中）→ DONE（已完成）
- 记录确认：confirmStatus PENDING（待确认）→ CONFIRMED（已确认）
- 上报：reportStatus DRAFT/SUBMITTED/REPORTING/REPORTED/RETRY_WAIT/FAILED_MAX/FAILED/CONFIRMED（8 态）

**新（roles §6，7 态 + 修订 v2）**：作业中 → 待配合人签字 → 待复核 → 待使用单位签署 → 已签署 →（待整改 ⇄）→ 已归档；修订生成 v2+新哈希。

#### 4.2.1 逐态映射

| 旧态/字段 | 新态 | 转移差异 | 合规影响 |
|---|---|---|---|
| WorkOrder PENDING | 作业中（前置） | 派工后未签到 | 派单不变量不动（AGENTS §3.6） |
| WorkOrder PROCESSING（签到后） | 作业中 | 签到→清单填写 | 签到坐标必填/三级阈值/双人动态码原子消费不变（§3.2/3.3） |
| WorkOrder DONE + 主检签名提交 | 待配合人签字 → 待复核 | **旧签退即完成→新双签链** | **2.6 上报时点必须保持在签退/作业完成（主检签名提交）时，不得推迟到使用单位签署链之后** |
| confirmStatus PENDING | 待使用单位签署 | 使用单位确认区 | CA client:sign；门户态互斥不变（§3.9） |
| confirmStatus CONFIRMED | 已签署 → 已归档 | 归档 | 旧 CONFIRMED 为本地终态不上报；新归档同样不触发额外上报 |
| reportStatus FAILED | （并行上报子态） | 上报失败标记 | **仅逐条人工重报，无自动重试**（§2.4）；上报子态与记录主态解耦 |
| —（新增） | 待整改 | 复核退回 | 异常项 S0—S7 显式判定不变（§3.4） |
| —（新增） | 修订 v2 | 归档后修订 | **v1 与签名永久保留**；MM 发起+PA 确认；**不触发任何上报**（P-5，docs/06 待确认） |

#### 4.2.2 双签核验三条件（新 record:review）

1. 主检签名存在（worker_signature_file_id，V23）；
2. 配合人签名存在（assistant_signature_file_id，V23）——**有配合人时**；
3. 作业时长 ≥ 30 分钟（APP_WORK_DURATION_MINUTES / config.minWorkDurationMinutes 双保险）。

> **P-4 待裁决**：存量单人作业记录（无配合人）vs 新强制双签。建议存量按"单人豁免"标记归档，不追溯补签；新记录强制双签。待用户确认。

#### 4.2.3 上报快照不变量（Blocker 级保留）

- 签退时冻结检查项与 reportPayload 快照，2.6 使用快照上报，不重新组装（AGENTS §3.5）。
- 新"待使用单位签署/已签署/已归档"链**不得改变 2.6 上报时点**——上报发生在主检签名提交（作业完成）时，使用单位签署是后置本地确认，不重报、不延迟上报。
- 修订 v2 只生成内部新版本+新哈希，**不触发 2.6**（P-5 确认前）。

### 4.3 急修/报修工单状态机

**旧（Fault，3 态）**：OPEN（处理中）→ ASSIGNED（已派单）→ CLOSED（已闭环）
（前端 UNIT_FAULT_STATUS_TEXT 缺 ASSIGNED，D-3；编号 BWJX+yyyyMMddHHmm+3位=19位）

**新（roles §6，7 态）**：待接单 → 已接单 → 处置中 → 待使用单位确认 →（待整改 ⇄）→ 已闭环；已取消。SLA 倒计时 + 抢单 + 代报修。

#### 4.3.1 逐态映射

| 旧态 | 新态 | 转移差异 | 合规影响 |
|---|---|---|---|
| OPEN | 待接单 | 新增 SLA 快照（sla_minutes/deadline 创建时冻结，读 sys_config） | 超时升级 order:escalate |
| ASSIGNED | 已接单 | 旧 dispatchFault OPEN→ASSIGNED；新派单/抢单 | 派单 order:dispatch；抢单 order:grab |
| （旧无显式处置态） | 处置中 | 新增到场/处置/提交 | order:arrive/process/submit；到场以签到为准（DirectoryService.firstCheckinToday） |
| （旧 CLOSED 前无确认） | 待使用单位确认 | 新增使用单位确认区 | order:confirm / CA client 域 |
| CLOSED | 已闭环 | 一致 | 闭环留痕 |
| —（新增） | 待整改 | 复核退回 | |
| —（新增） | 已取消 | 取消 | order:cancel |

#### 4.3.2 编号与代报修

- **faultNo BWJX 编号保留**（DirectoryService.nextFaultNo L216-222）；新设计 JX/RX 前缀仅原型演示，不改生产编号规则。
- 代报修新增字段：proxy_source=PHONE/WECHAT/ONSITE、recording_no `^REC-\d{4,6}$`、proxy_by（fault 表加列，V35+）。
- 旧 WorkOrder 中的 FM 救援工单（困人救援/救援后复查）并入新 order 域时，保留原 work_order 记录与上报快照，不重新组装 2.6。

### 4.4 维保计划池状态机（ScheduleView 配套）

**旧（MaintainPlan，5 态）**：UNASSIGNED → ASSIGNED →（POSTPONE_PENDING ⇄）→ DISPATCHED；CANCELLED

| 旧态 | 新落点 | 转移差异 | 合规影响 |
|---|---|---|---|
| UNASSIGNED | #/plans 待派单 | 定时任务扫描到期前一日生成 | **派单只由定时任务驱动**（每日 09:00 主扫描+每 13 分钟兜底；AGENTS §3.6） |
| ASSIGNED | #/plans 已指派 | 指派主检/配合 | task:assign_partner |
| POSTPONE_PENDING | #/plans 延期审批 | 申请延期→审批 | plan:generate（手动） |
| DISPATCHED | #/plans 已派单 | 计划时间=到期日 09:00 | 同项目同日到期一次性全部派单 |
| CANCELLED | #/plans 已取消 | | |

> **D-A1**：新 plan:generate 不做"按合同周期批量生成 N 条"；计划视图=只读投影+手动派单/延期。到期基线按类型取最近一次同类型已完成维保；从未执行以首保日期起算；无已完成回退建档 lastMaintenanceAt（AGENTS §3.6）。

### 4.5 外接建档状态机（FilingView 配套，合规保留）

**旧（FILING_STATUS，5 态）**：INIT → DOC_COLLECTING → TAKEOVER_CHECKING → PENDING_CONFIRM → ARCHIVED

| 旧态 | 新落点 | 合规约束 |
|---|---|---|
| INIT | 外接建档·草稿 | 合同前置 |
| DOC_COLLECTING | 资料收集 | 缺件待补办 |
| TAKEOVER_CHECKING | 接管查验 | 责任归属必填 |
| PENDING_CONFIRM | 待确认 | |
| ARCHIVED | 已归档 | archive_source=2 + 首保+3日（区别于移交 archive_source=1+15日） |

> 外接建档状态机本期**不改**，原样迁入监管与档案组（D-A4）。

### 4.6 合规影响汇总（M4 转移测试门禁）

| 不变量 | 出处 | 新状态机下的保留要求 |
|---|---|---|
| 派单只由定时任务驱动，读接口不触发 | AGENTS §3.6 | plans 视图=只读投影；plan:generate 仅手动 |
| 2.6 上报时点=签退/作业完成 | AGENTS §3.5 | 记录主态新增双签/使用单位链**不延迟上报** |
| 无自动重试，仅逐条人工重报 | AGENTS §2.4 | report:retry 禁批量；修订 v2 不触发上报 |
| 签到坐标/三级阈值/双人动态码原子消费 | AGENTS §3.2/3.3 | 作业中态前置校验不变 |
| 签退快照冻结 | AGENTS §3.5 | 修订 v2 不重新组装快照 |
| 文件时效签名 ?s= | AGENTS §2.5 | 所有附件读路径重签 |
| 单身份登录互斥 | AGENTS §3.9 | 门户态独立；多角色在员工态内切换工作台 |
| 工单归属写谓词严格 | AGENTS §3.1 | 范围扩大只进读谓词；防代签到代签退 |
| archive() 副作用（建档+首保） | HandoverService.confirm | 移交"已生效"转移保留 |
| 异常项 S0—S7 显式判定 | AGENTS §3.4 | 待整改态不静默补 S0 |

---

## 5. 修订记录

| 版本 | 日期 | 修订内容 |
|---|---|---|
| V1.0 | 2026-11-14 | 首版：M0 四张对照表（角色映射+账号迁移、24→71+ 权限码映射+扩展码+导航推导、21→23 视图操作级映射、三套状态机+计划池+外接建档对照）；登记 5 项待裁决（P-1—P-5）与 4 项现存缺陷（D-1—D-4）；标注 order/fault 语义冲突与 OrderListView 混合域拆分两处迁移陷阱 |