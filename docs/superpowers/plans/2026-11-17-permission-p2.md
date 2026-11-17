# 权限 P2 待办实施计划（C-24/C-25/N+1，不改表）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 落地权限 P2 优先级待办——C-24 员工内部读记录详情端点（真实环境 record-detail 员工链路可用）、C-25②④⑤⑦⑧ minor、N+1 性能批量化。不改数据库表、不动客户 T4 门禁、内部 11 角色行为不变。

**Architecture:** 新增员工内部读记录端点 `GET /records/{id}`（record:view 门控 + V7 班组谓词 + 内部投影 toMap 补生命周期字段），record-detail 页改走内部端点；token 匿名端点 404/401 归一消除存在性 oracle；faults 投影补 elevatorName；sign 路由 auth:false（token 即凭证）+ pkg-admin 死配置清理；N+1 抽共享批量最新版本 helper。

**Tech Stack:** Java 17/Spring Boot 3.3.4/MyBatis-Plus/JUnit5；微信小程序 CommonJS；mock Node；Vitest（admin 不涉及）。

**Spec:** docs/12-未完成事项清单.md C-24/C-25（L95-96）、I 组；docs/14 V2.3 §4.6/§7.3。**注意区分**：本 P2 是「优先级 P2 待办」（不改表），非 I 组权限重构 P2「租户快照」（那需 V47 迁移，不在本计划）。

## Global Constraints

- **不改数据库表、不新增 Flyway 迁移**（trapped 依赖 C-18 增列 → 缓议；C-25② 仅补 elevatorName 投影）。
- **客户 T4 门禁不动**（UnitRecordService.getAs/requireClient/requireViewable/requireSignable/toClientMap 客户路径零改动；docs/12 L95「P1 不扩权改 T4 门禁」）。
- 冻结面禁改：RbacMatrixTest/AdminRoleInterceptorCompatTest/LegacyCapabilityTranslatorTest/legacy GRANTS。86 码/13 角色不变。
- 合规红线（AGENTS §2/§3）：越权 403/1403 无副作用+留审计；不触发 2.6；不打印敏感字段；员工内部读端点须 V7 班组谓词收敛（不得全量泄漏）。
- 接口三处同步（AGENTS §1.2）：新端点须 services/ + mock/server.js + docs/04 A 部分同步。
- 错误用 BizException；中文注释；时间 yyyy-MM-dd HH:mm:ss。
- 验证：backend `mvn test`；根 `npm run lint`；`node scripts/verify-miniapp-routes.js`、`verify-m1-sync.js`、`verify-review-fixes.js`、`verify-p1-mock.js`、`verify-dispatch.js`、`verify-templates.js`。
- 分支 codex/permission-p2；提交一行中文动词开头。

## Review Focus

1. **员工内部读端点数据域**：新 GET /records/{id} 必须套 V7 班组谓词（visibleReadPlatformIds + workerPlatformId/assistantPlatformId），MAINT_TECH 不得读他班组记录（1403）；PRIVILEGED/DEPT_WIDE 全量；NO_FIELD 角色空集。不得做成全量泄漏。T1 测试钉死。
2. **客户门禁零回归**：新端点不得改变 /unit/records 客户路径（getAs/requireClient/toClientMap）；UnitTokenScopeTest maintTokenCannotReadPortalRecords（portal pendingAs 拒员工）保持绿。T1。
3. **存在性 oracle 消除**：token 端点不存在 id 与错 token 同返 401（不泄漏 1404）；幂等 guard 顺序不破坏。T2。
4. **内部投影字段**：toMap 补 lifecycleStatus/version/prevVersionId/hashPrefix 后 record-detail 生命周期状态条/版本核对真实可用；shareToken 内部投影保留（copySignLink 依赖）但客户路径仍排除。T1。
5. **N+1 批量化等价**：批量最新版本 helper 与逐条 isLatestVersion 结果逐集合等价；不改变可见/可签判定。T5。

---

### Task 1: C-24 员工内部读记录详情端点

**Files:**
- Create: `backend/.../controller/RecordController.java`（GET /records/{id}）
- Modify: `backend/.../service/UnitRecordService.java`（toMap 补 lifecycleStatus/version/prevVersionId/hashPrefix；新增 getForEmployee(id, empId) 或在 RecordController 组合 scope+toMap）
- Modify: `backend/.../service/EmployeeScopeService.java`（复用 visibleReadPlatformIds，无需改）
- Modify: `pkg-maintenance/pages/record-detail/index.js`（数据源改内部端点）
- Modify: `services/order.js`（新增 getRecordDetail(id)→GET /records/{id}）或 services/unit.js
- Modify: `mock/server.js`（新增 GET /records/:id 内部投影，record:view + 班组谓词镜像；员工桥接 B7 同步）
- Modify: `docs/04-接口文档.md`（A 部分新端点，T6 统一）
- Test: 新增 `backend/.../controller/RecordControllerTest.java`（真实谓词，不 mock service）；`UnitRecordServiceTest`（toMap 新字段）

**Interfaces:**
- Produces: `GET /records/{id}`（MultiRoleGate.requireRead(record:view) + V7 班组谓词 + toMap 内部投影）；`services/order.js getRecordDetail(id)`。T6 mock/docs 同步。
- Consumes: MultiRoleGate.requireRead（样例 WorkDomainController L34）；EmployeeScopeService.visibleReadPlatformIds（样例 WorkOrderService.scopeOrders L280-287）；UnitRecordService.findOr404/toMap。

**设计裁决（控制器）：**
- 新端点 `GET /records/{id}` 放新 RecordController（记录域内部读，与 WorkDomainController /records/{id}/amend 同前缀）。门禁 `MultiRoleGate.requireRead(req, PermissionMatrix.RECORD_VIEW)`（Y=MS/MM/PA/SA，C=MT）。
- 数据域：取 Employee（EmployeeScopeService.require(empId)）→ visibleReadPlatformIds(e)；null=不限；非 null 须 contains(r.workerPlatformId)||contains(r.assistantPlatformId)，否则 1403「仅可查看本人或本班组记录」。空集→任何记录 1403。
- 投影 toMap **补 lifecycleStatus/version/prevVersionId/hashPrefix**（hashPrefix=archiveHash 前 8，复用 signView L257-262 逻辑）；toMap 已含 shareToken/context/items 明细（内部投影，copySignLink 依赖 shareToken）。record-detail 页 L56 读 recordVersion→改读 version（或投影加 recordVersion 别名，择一）。
- record-detail 页（员工 MAINT_EXT）数据源由 services/unit.js getRecord(/unit/records) 改 services/order.js getRecordDetail(/records)。services/unit.js getRecord 仅 record-detail 用→改后标契约保留或删（admin/src/api/report.js getRecord 死导出不动）。
- **客户路径零改动**：/unit/records getAs/requireClient/toClientMap 不动；UnitTokenScopeTest L101 portal pendingAs 拒员工保持。
- mock：新增 GET /records/:id（record:view requireRead + 班组谓词镜像 + 内部投影含 lifecycleStatus/version/shareToken）；verify-p1-mock B7 员工桥接断言同步到新端点。

- [ ] **Step 1: 写失败测试** RecordControllerTest（真实 EmployeeScopeService+UnitRecordService，仅 mock mapper）

```java
// MAINT_TECH 读本人记录 200（workerPlatformId 匹配）；读他班组记录 1403
// MAINT_SUP 读本组 200；MAINT_MGR/PLATFORM_ADMIN 全量 200
// NO_FIELD 角色（MAINT_BIZ）读任何 1403（空集）
// 无 record:view 角色（INSTALLER）403；客户令牌 403（requireRead record:view DENY）
// 投影含 lifecycleStatus/version/prevVersionId/hashPrefix/shareToken/items 明细
// 不存在 id 1404（内部端点非匿名，1404 正常）
```
UnitRecordServiceTest 补 toMap 含 lifecycleStatus/version/prevVersionId/hashPrefix 断言。

- [ ] **Step 2: 跑确认失败** `mvn test -Dtest=RecordControllerTest,UnitRecordServiceTest`（端点不存在/toMap 缺字段 FAIL）。

- [ ] **Step 3: 实现** RecordController GET /records/{id}（requireRead + scope + toMap）；toMap 补字段；record-detail 页改 getRecordDetail；services/order.js 增方法；mock 新端点 + B7 同步。

- [ ] **Step 4: 跑确认通过** `mvn test -Dtest=RecordControllerTest,UnitRecordServiceTest,UnitTokenScopeTest,ClientRecordGateTest`（客户门禁零回归）；`node scripts/verify-p1-mock.js`、`verify-m1-sync.js`。

- [ ] **Step 5: 提交** `git commit -m "feat: 员工内部读记录详情端点（record:view+V7班组谓词+生命周期投影），record-detail真实环境可用（C-24）"`

---

### Task 2: C-25⑧ token 端点 404/401 归一

**Files:**
- Modify: `backend/.../service/UnitRecordService.java`（signView L229/confirmByToken L271 findOr404→401 归一）
- Modify: `backend/.../service/RecordLifecycleService.java`（refuseByToken L143 findOr404→401 归一）
- Modify: `mock/server.js`（sign-view L843/confirm-by-token L869/refuse-by-token L405 同步）
- Test: `ClientRecordGateTest`/`RecordLifecycleTest`（不存在 id→401 断言）

**设计裁决：** token 校验依赖 r.shareToken（须先载记录），无法「先 token 后查」。采用 **404/401 归一**：三匿名端点记录不存在时抛 401「确认链接无效或已失效」（与错 token 同文案），消除 1404/401 存在性 oracle。新增 private findOr401（null→401）替换三处 findOr404。幂等 guard.begin 顺序保持（控制器 L98/L120 先于服务调用，不动）。内部 getAs/findOr404（非匿名）保持 1404。

- [ ] **Step 1: 写失败测试** 三匿名端点不存在 id→401（非 1404），错 token→401，存在+正确 token 正常。
- [ ] **Step 2: 跑确认失败** `mvn test -Dtest=ClientRecordGateTest,RecordLifecycleTest`（不存在 id 原 1404 FAIL）。
- [ ] **Step 3: 实现** 三处 findOr404→findOr401；mock 同步。
- [ ] **Step 4: 跑确认通过** `mvn test -Dtest=ClientRecordGateTest,RecordLifecycleTest,UnitRecordConfirmTest`（内部 confirm 1404 保持）；`node scripts/verify-p1-mock.js`。
- [ ] **Step 5: 提交** `git commit -m "fix: token匿名端点不存在记录归一401，消除存在性oracle（C-25⑧）"`

---

### Task 3: C-25② faults 投影补 elevatorName

**Files:**
- Modify: `backend/.../service/UnitPortalService.java`（faults L162-172 补 elevatorName，批量联 Elevator）
- Modify: `mock/server.js`（/unit/faults 投影对齐，已回整行含 elevatorName，确认一致）
- Test: `UnitPortalServiceTest`（faults 投影含 elevatorName）

**设计裁决：** Fault 无 elevatorName 列→按 elevatorCode 批量 `.in(elevatorCode,codes)` 查 Elevator 建 code→name map，投影补 elevatorName（缺失回退 elevatorCode）。**trapped 依赖 C-18 增列，不改表缓议**（docs/12 登记）。消费页 orders 已 roles:[] 下线，验收以契约/单测为准。

- [ ] **Step 1: 写失败测试** faults 行含 elevatorName（批量联电梯，无 N+1）；跨单位仍过滤。
- [ ] **Step 2: 跑确认失败** `mvn test -Dtest=UnitPortalServiceTest`。
- [ ] **Step 3: 实现** 批量电梯 map + 投影补 elevatorName。
- [ ] **Step 4: 跑确认通过** `mvn test -Dtest=UnitPortalServiceTest`；`node scripts/verify-p1-mock.js`。
- [ ] **Step 5: 提交** `git commit -m "feat: 客户急修列表投影补电梯名（批量联电梯，C-25②；trapped待C-18）"`

---

### Task 4: C-25④⑤ 路由配置（sign auth:false + pkg-admin 死配置）

**Files:**
- Modify: `config/routes.js`（L63 sign 加 auth:false；L70/71/73/74 删 CLIENT_ADMIN）
- Test: `scripts/verify-miniapp-routes.js`（跑绿）；守卫语义核查

**设计裁决：**
- C-25④：sign 页 `auth:false`（token 即凭证，guard.js L30 全 bypass；后端匿名白名单 WebConfig L39-40 已就绪；nav.js L13 回跳白名单已含 sign）。保留 roles/perm 登记供参考但 auth:false 优先生效。docs/14 §4.6 规则 8 P1/P2 兼容期匿名链路保留。
- C-25⑤：routes.js contracts/users/stats/audit 四页 roles 删 CLIENT_ADMIN（T2 收回后客户不持四码，perm 层已挡，roles 死配置；先例 M6 handover detail）。

- [ ] **Step 1: 改配置** sign auth:false + 四页删 CLIENT_ADMIN。
- [ ] **Step 2: 跑守卫** `node scripts/verify-miniapp-routes.js`、`verify-miniapp-m0.js`、根 `npm run lint`。
- [ ] **Step 3: 提交** `git commit -m "fix: sign路由auth:false保留匿名token凭证，清理pkg-admin四页CLIENT死配置（C-25④⑤）"`

---

### Task 5: N+1 批量化

**Files:**
- Modify: `backend/.../service/UnitRecordService.java`（pendingAs L61 批量；抽共享 helper）
- Modify: `backend/.../service/UnitPortalService.java`（visibleRecords L132 批量）
- Modify: `backend/.../auth/MiniappMenuService.java`（clientPendingSigns L240 批量）
- Modify: `backend/.../service/AdminArchiveService.java`（employeeList customerPrincipal L157 批量 sys_user_role）
- Create/Modify: 共享批量最新版本 helper（如 MaintainRecordMapper 默认方法或 util）
- Test: 既有 UnitRecordServiceTest/UnitPortalServiceTest/MiniappMenuServiceTest/ClientExclusionTest（等价零回归）+ 批量 helper 单测

**设计裁决：**
- 抽共享 `Set<String> supersededIds(Collection<String> ids)`：一次 `selectList(.in(prevVersionId, ids))` 取 prevVersionId 集（=被替代的记录 id）；isLatest(id)=!superseded.contains(id)。替换三处逐条 selectCount（UnitRecordService L171/UnitPortalService L141/MiniappMenuService L240）。
- pendingAs：批量电梯 code→useUnitId map（一次 `.in(elevatorCode,codes)`）+ 批量 supersededIds，内存过滤等价。
- employeeList：批量 sys_user_role `.in(userId,empIds).eq(userType,EMPLOYEE)` 按 userId 分组，customerPrincipal 用 map（无 N 查询）。
- **等价性**：批量结果与逐条逐集合相等；可见/可签判定不变。

- [ ] **Step 1: 写失败测试** 批量 helper supersededIds 与逐条 isLatestVersion 等价（含空集/单条/多条/链末端）；pendingAs/employeeList 批量后行为等价。
- [ ] **Step 2: 跑确认失败** `mvn test -Dtest=<helper测试>`。
- [ ] **Step 3: 实现** helper + 四处批量化。
- [ ] **Step 4: 跑确认通过** `mvn test`（全量零回归，冻结面绿）。
- [ ] **Step 5: 提交** `git commit -m "perf: 记录最新版本/电梯归属/员工角色批量化消除N+1（共享supersededIds helper）"`

---

### Task 6: 回归 + 文档同步

**Files:**
- Modify: `docs/04-接口文档.md`（A 部分 GET /records/{id} 员工内部读端点 + token 端点 401 归一口径 + faults elevatorName；修订记录）
- Modify: `docs/12-未完成事项清单.md`（C-24 ✅、C-25②④⑤⑧ ✅、⑦发版注记、N+1 ✅；trapped 待 C-18；修订记录）
- Modify: `AGENTS.md`（§10 P2 修订记录 + 计数实测；AOCI 块不动）

- [ ] **Step 1: 全量门禁** backend `mvn clean test`；根 `npm run lint`；verify-miniapp-routes/m0/m1-sync/review-fixes/p1-mock/dispatch/templates。automator 若 IDE 可用跑 record-detail 员工链路（C-24 闭环），不可用标注人工门禁。
- [ ] **Step 2: 文档同步** docs/04 新端点三处同步 + docs/12 状态 + AGENTS 修订/计数。C-25⑦ 旧版 orderId→sign-request 1404 发版注意项登记（后端不做兼容）。
- [ ] **Step 3: 提交** `git commit -m "docs: 权限P2待办落地回写（C-24/C-25/N+1），新端点三处同步"`

---

## Self-Review 结论
- Spec 覆盖：C-24→T1；C-25⑧→T2；②→T3；④⑤→T4；⑦→T6 登记；N+1→T5；①③⑥ 随 P3 不动。
- 类型一致：getRecordDetail（T1）/supersededIds（T5）/findOr401（T2）命名统一。
- Review Focus 落点：数据域→T1；客户零回归→T1；oracle→T2；投影字段→T1；批量等价→T5。
- 不改表/客户门禁不动/冻结面禁改贯穿。trapped（C-18）/C-25①③⑥（P3）明确排除。