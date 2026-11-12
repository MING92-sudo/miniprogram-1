# 电梯移交 + 外接建档 + 使用单位门户 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有无纸化维保系统上，新增「安装移交」「外接建档」两条电梯建档路径与「使用单位登录门户」，并补「区域划分」「维保日历」两项横切能力，实现建档到首保的自动衔接。

**Architecture:** 以 `elevator` 主表为共享底座，两条建档路径各自一张状态机单据，归档时触发一个事务性、幂等的「归档动作」——写电梯主表 + 绑定责任人 + 立即预建首保工单（半月保 HM）。使用单位以「一个单位一个账号」登录，只读本 `useUnitId` 下电梯与记录，报修复用现有急修链路。

**Tech Stack:** Spring Boot 3.3.4 + MyBatis-Plus + Flyway（V24+ 只追加）、微信小程序（CommonJS）、Vue3 + Element Plus 管理端、Vitest（admin）、mvn test（backend）。

**Spec:** `docs/superpowers/specs/2026-11-12-handover-filing-unit-portal-design.md`

## Global Constraints

- Java 17 / Spring Boot 3.3.4；统一 `ApiResponse` 返回；写操作先归属/状态/参数校验再落库。
- Flyway 迁移只追加新版本，不改已执行迁移；新迁移不得与 V1—V23 冲突。
- 接口三处同步：`services/` + `mock/server.js` + `docs/04`；新页面注册进 `app.json`。
- 错误统一 `AppError`/`BizException`；禁止 `any`；小程序禁止页面直接 `wx.request`。
- 工单号只经 `OrderNoIssuer`；写操作带 `X-Idempotency-Key`；派单只由定时任务驱动，读接口不触发副作用。
- 越权一律 `1403` 且无副作用；时间一律 `yyyy-MM-dd HH:mm:ss`（GMT+8）。
- 目录级检查：backend `mvn test`；admin `npm run lint && npm test && npm run build`；根 `npm run lint`。

## Review Focus

1. **重复归档**：同一移交单/外接建档单重复提交归档，不得重复建电梯、重复建首保工单（终态 + 幂等键防重）。
2. **首保与定时派单冲突**：首保预建后（PENDING），现有 `DispatchService` 不得再给同一电梯派单（已有未完成工单即跳过）。
3. **越权读**：安装部/使用单位/维保人员各只能看自己范围内的数据（使用单位 `useUnitId`、安装部来源单、区域可见范围），越权 `1403`。
4. **移交必传卡点**：监督检验报告/维保说明书/整机质量证明任一缺失，禁止从「资料移交中」提交。
5. **外接合同前置**：无已生效合同或合同起始日晚于建档日，拒绝建档；同梯已关联其他生效合同提示冲突。

---

## 文件结构

### 后端新增（`backend/src/main/java/com/cqwlw/maintenance/`）
- `entity/`：`Region.java`、`HandoverOrder.java`、`HandoverCheck.java`、`HandoverCheckItem.java`、`HandoverRectification.java`、`HandoverSign.java`、`ExternalFilingOrder.java`、`ExternalFilingDoc.java`、`ExternalTakeoverCheck.java`、`ExternalTakeoverCheckItem.java`、`HandoverConfirmation.java`、`MaintenanceContract.java`、`ContractElevator.java`
- `service/`：`RegionService.java`、`HandoverService.java`、`ExternalFilingService.java`、`ContractService.java`、`UnitPortalService.java`、`MaintenanceCalendarService.java`
- `controller/`：`RegionController.java`、`HandoverController.java`、`ExternalFilingController.java`、`ContractController.java`、`UnitPortalController.java`、`MaintenanceCalendarController.java`
- `mapper/`：上述实体各一个 `*Mapper.java`
- 修改：`entity/Elevator.java`、`entity/Employee.java`、`entity/WorkOrder.java`、`entity/UseUnit.java`、`auth/AdminRoleInterceptor.java`、`service/DispatchService.java`（首保复用 `dispatchNow` 逻辑抽公共方法）
- `resources/db/migration/`：`V24__region.sql`、`V25__elevator_archive_source.sql`、`V26__contract.sql`、`V27__handover.sql`、`V28__handover_issue.sql`、`V29__external_filing.sql`、`V30__use_unit_account.sql`

### 小程序新增（`pages/`）
- `handover/`：`init`、`detail`、`inspect`、`rectify`、`confirm`
- `filing/`：`init`、`detail`、`inspect`、`confirm`
- `unit/`：`home`、`elevators`、`records`、`faults`、`report`
- `calendar/`：`index`
- 修改：`app.json`（注册页面 + 自定义 tabBar 预留）、`services/handover.js`、`services/filing.js`、`services/unit.js`（补门户接口）、`services/contract.js`

### 管理端新增/修改（`admin/src/views/`）
- 新增：`HandoverView.vue`、`FilingView.vue`、`ContractView.vue`、`RegionView.vue`、`UnitAccountView.vue`
- 修改：`ElevatorsView.vue`、`LedgerView.vue`（区域列）、`router/index.js`、菜单

---

## Task 1: 区域主数据 + 电梯/员工区域字段

**Files:**
- Create: `backend/.../entity/Region.java`、`mapper/RegionMapper.java`、`service/RegionService.java`、`controller/RegionController.java`
- Create: `backend/src/main/resources/db/migration/V24__region.sql`
- Modify: `entity/Elevator.java`（`regionId`）、`entity/Employee.java`（`regionId`）

**Interfaces:**
- Produces: `RegionService.list()/create()/update()/delete()`；`GET/POST/PUT/DELETE /regions`；`PUT /elevators/{id}/region`、`PUT /employees/{id}/region`。

- [ ] **Step 1: 写迁移与实体**：`V24__region.sql` 建 `region` 表（id/name/sort_order/remark）并给 `elevator`、`sys_employee` 加可空 `region_id` 列；`Region.java` 实体字段对齐。
- [ ] **Step 2: 写失败测试**：`RegionServiceTest.listCreatesAndFilters`（建区域→查列表→断言 name 存在）。
- [ ] **Step 3: 跑测试确认失败**：`mvn test -Dtest=RegionServiceTest` → FAIL（`RegionMapper` 未定义）。
- [ ] **Step 4: 实现** `RegionService` + `RegionController`（CRUD + 分配接口，越权 1403）。
- [ ] **Step 5: 跑测试确认通过** → PASS。
- [ ] **Step 6: Commit**：`feat: 新增区域主数据与电梯/员工区域字段`。

## Task 2: 电梯建档字段 + 工单来源字段

**Files:**
- Create: `backend/src/main/resources/db/migration/V25__elevator_archive_source.sql`
- Modify: `entity/Elevator.java`（`archiveSource`、`supervisionPassDate`、`handoverAt`）、`entity/WorkOrder.java`（`sourceHandoverId`、`sourceFilingId`、`handoverIssue`）

**Interfaces:**
- Produces: 电梯 `archiveSource`（int，0/1/2）、`supervisionPassDate`、`handoverAt`；工单 `sourceHandoverId`、`sourceFilingId`、`handoverIssue`。

- [ ] **Step 1: 写迁移**：`elevator` 加 `archive_source`（default 0）、`supervision_pass_date`、`handover_at`；`work_order` 加 `source_handover_id`、`source_filing_id`、`handover_issue`（default false）。
- [ ] **Step 2: 更新实体字段**（对齐列名，`@TableField` 注明非驼峰映射处）。
- [ ] **Step 3: 跑后端全量测试**：`mvn test` → PASS（无回归）。
- [ ] **Step 4: Commit**：`feat: 电梯建档来源与工单来源/遗留问题字段`。

## Task 3: 维保合同（最小版）

**Files:**
- Create: `entity/MaintenanceContract.java`、`entity/ContractElevator.java`、`mapper/*`、`service/ContractService.java`、`controller/ContractController.java`
- Create: `backend/src/main/resources/db/migration/V26__contract.sql`

**Interfaces:**
- Produces: `ContractService.create()`（校验起始日 ≤ 建档日）、`getActiveContractByElevator(elevatorId)`；`POST /contracts`、`GET /contracts`、`GET /contracts/{id}`。

- [ ] **Step 1: 写迁移与实体**：`maintenance_contract`（contract_no/use_unit_id/start_date/end_date/status/amount）、`contract_elevator`（contract_id + elevator_id 唯一约束）。
- [ ] **Step 2: 写失败测试**：`ContractServiceTest.rejectsContractStartingAfterFilingDate`（起始日晚于建档日 → 抛 422）；`rejectsOverlappingActiveContract`（同梯已有生效合同 → 冲突提示）。
- [ ] **Step 3: 跑测试确认失败** → FAIL。
- [ ] **Step 4: 实现** `ContractService` + `ContractController`。
- [ ] **Step 5: 跑测试确认通过** → PASS。
- [ ] **Step 6: Commit**：`feat: 维保合同最小版（建合同/合同电梯关联/生效期校验）`。

## Task 4: 移交单状态机 + 必传卡点 + 现场查验

**Files:**
- Create: `entity/HandoverOrder.java`、`entity/HandoverCheck.java`、`entity/HandoverCheckItem.java`、`entity/HandoverRectification.java`、`entity/HandoverSign.java`、`mapper/*`
- Create: `backend/src/main/resources/db/migration/V27__handover.sql`
- Create: `service/HandoverService.java`、`controller/HandoverController.java`

**Interfaces:**
- Produces: `HandoverService.init()`（安装部发起）、`submitDocs()`（必传卡点）、`assignReceiver()`（维保部经理分配接收人员）、`submitInspect()`（查验 + 安全装置/制动器不合格→整改）、`submitRectify()`（整改前后照片）、`recheckRectify()`（复验通过回待确认）、`confirm()`（经理确认）、`archive()`（触发归档动作，见 Task 5）。
- 状态枚举：`INIT`→`DOC_SUBMITTING`→`INSPECTING`→（`RECTIFYING`⇄）→`PENDING_CONFIRM`→`ARCHIVED`。

- [ ] **Step 1: 写迁移与实体**：`handover_order`、`handover_check`、`handover_check_item`、`handover_rectification`、`handover_sign`。
- [ ] **Step 2: 写失败测试**：`HandoverServiceTest.submitDocsBlocksOnMissingRequired`（缺监督检验报告/维保说明书/整机质量证明 → 422 拦截）；`inspectForceRectifyOnSafetyFail`（安全装置不合格 → 状态转 RECTIFYING）；`recheckReturnsToPendingConfirm`（复验通过回待确认）。
- [ ] **Step 3: 跑测试确认失败** → FAIL。
- [ ] **Step 4: 实现** `HandoverService` + `HandoverController` + 现场查验清单自动生成（按提交电梯类别生成查验项）。
- [ ] **Step 5: 跑测试确认通过** → PASS。
- [ ] **Step 6: Commit**：`feat: 移交单状态机与必传/查验/整改闭环`。

## Task 5: 归档事件 + 首保工单闭环

**Files:**
- Create: `backend/src/main/resources/db/migration/V28__handover_issue.sql`（`handover_order` 加 `handover_issue` 遗留问题标记列）
- Modify: `service/HandoverService.java`（补 `archive()`）、`service/DispatchService.java`（抽 `precreateFirstOrder(elevator, planDate)` 公共方法）、`service/WorkOrderService.java`（首保签退回写遗留问题）

**Interfaces:**
- Consumes: Task 2 电梯/工单字段、Task 4 移交状态机。
- Produces: `HandoverService.archive()` 事务内：写电梯 `archiveSource=1` + `supervisionPassDate`/`handoverAt` + 责任人（接收工程师）+ 关联资料 + 调用 `DispatchService.precreateFirstOrder()`（首保 HM，planTime=归档日+15日）。

- [ ] **Step 1: 写失败测试**：`HandoverArchiveTest.archiveCreatesElevatorAndFirstOrder`（归档后：电梯 `archiveSource=1`、责任人=接收工程师、存在首保工单 `workTypeCode=HM` 且 `sourceHandoverId` 回指移交单）；`archiveIsIdempotent`（重复归档不重复建工单）；`archivePrecreatedOrderBlocksScheduledDispatch`（归档预建首保后，定时派单扫描不再给该电梯派新单）。
- [ ] **Step 2: 跑测试确认失败** → FAIL。
- [ ] **Step 3: 实现** `archive()` + `DispatchService.precreateFirstOrder()`（复用 `createOrder` 逻辑，code=HM，planTime 可指定未来日期）。
- [ ] **Step 4: 跑测试确认通过** → PASS。
- [ ] **Step 5: 跑派单回归**：`node scripts/verify-dispatch.js` → 通过。
- [ ] **Step 6: 写失败测试**：`HandoverIssueClosureTest.checkoutWritesBackAndNotifies`（首保签退时 `handoverIssue=true` → 回写来源移交单标记遗留问题并通知安装部）。
- [ ] **Step 7: 跑测试确认失败** → FAIL。
- [ ] **Step 8: 实现** `WorkOrderService.checkout` 中当 `sourceHandoverId` 非空且 `handoverIssue=true` 时回写移交单 + 通知安装部。
- [ ] **Step 9: 跑测试确认通过** → PASS。
- [ ] **Step 10: Commit**：`feat: 移交归档事件与首保工单闭环（建电梯+首保+责任人+遗留问题回写）`。

## Task 6: 移交内部账号节点签字

**Files:**
- Modify: `service/HandoverService.java`（补 `signNode()`）

**Interfaces:**
- Consumes: `handover_sign` 表（Task 4）。
- Produces: `signNode(handoverId, nodeType, action)` 记录账号节点确认（账号名自动填写，`action`=通过/驳回）。

- [ ] **Step 1: 写失败测试**：`HandoverSignTest.signNodeRecordsAccountName`（签字后 `handover_sign` 有 `actor_name` 且 `action` 正确）；`signNodeRejectsNonAssignedRole`（非该节点角色签字 → 1403）。
- [ ] **Step 2: 跑测试确认失败** → FAIL。
- [ ] **Step 3: 实现** `signNode()`。
- [ ] **Step 4: 跑测试确认通过** → PASS。
- [ ] **Step 5: Commit**：`feat: 移交节点账号签字（内部账号自动填名）`。

## Task 7: 外接建档状态机 + 合同前置 + 接管查验 + 交接确认

**Files:**
- Create: `entity/ExternalFilingOrder.java`、`entity/ExternalFilingDoc.java`、`entity/ExternalTakeoverCheck.java`、`entity/ExternalTakeoverCheckItem.java`、`entity/HandoverConfirmation.java`、`mapper/*`
- Create: `backend/src/main/resources/db/migration/V29__external_filing.sql`
- Create: `service/ExternalFilingService.java`、`controller/ExternalFilingController.java`

**Interfaces:**
- Consumes: Task 3 合同校验。
- Produces: `ExternalFilingService.init()`（校验已生效合同）、`submitDocs()`（缺资料可标 missing 待补办）、`submitTakeoverCheck()`（不合格不阻塞、责任归属必填）、`confirm()`（交接确认双方签字）、`archive()`（`archiveSource=2`，首保 planTime=接收日+3日）。

- [x] **Step 1: 写迁移与实体**：`external_filing_order`、`external_filing_doc`、`external_takeover_check`、`external_takeover_check_item`、`handover_confirmation`。
- [x] **Step 2: 写失败测试**：`ExternalFilingServiceTest.initRequiresActiveContract`（无合同 → 422）；`takeoverCheckRecordsResponsibilityWithoutBlocking`（不合格项记录责任方、不阻塞到待确认）；`missingDocGeneratesBackfillTask`（标缺失 → 生成补办任务）。
- [x] **Step 3: 跑测试确认失败** → FAIL。
- [x] **Step 4: 实现** `ExternalFilingService` + `ExternalFilingController` + 归档事件（复用 Task 5 公共方法）。
- [x] **Step 5: 跑测试确认通过** → PASS。
- [x] **Step 6: Commit**：`feat: 外接建档状态机与合同前置/接管查验/交接确认`。

## Task 8: 区域可见范围权限谓词

**Files:**
- Modify: `service/ElevatorService.java`（列表/档案查询）、`service/WorkOrderService.java`（工单查询）、`auth/AdminRoleInterceptor.java`

**Interfaces:**
- Consumes: Task 1 `region_id`。
- Produces: 维保人员列表/详情谓词 = 本区域电梯（`elevator.region_id = 本人 region_id`）；组长额外本班组；使用单位按 `useUnitId`。
- **落地口径（2026-11-12）**：读谓词 `EmployeeScopeService.visibleReadPlatformIds`（区域 ∪ 本班组 ∪ 本人，仅电梯列表与工单列表）；写谓词 `visibleWorkerPlatformIds` 保持严格——区域不得扩到签到/清单/签退/签字，否则等于代签到（AGENTS §3.1）。电梯档案详情与工单详情因暴露管理员电话/签字照片，仍走严格谓词；角色扩展由 P1 的 `PermissionMatrix` 承接。

- [x] **Step 1: 写失败测试**：`RegionScopeTest.workerSeesOnlyOwnRegionElevators`（区域 A 员工查不到区域 B 电梯）；`leaderSeesRegionPlusGroup`（组长可见本区域 + 本班组）。
- [x] **Step 2: 跑测试确认失败** → FAIL。
- [x] **Step 3: 实现** 区域谓词 + 拦截器角色扩展（INSTALLER/USE_UNIT）。
- [x] **Step 4: 跑测试确认通过** → PASS。
- [x] **Step 5: Commit**：`feat: 区域可见范围权限谓词与角色扩展`。

## Task 9: 使用单位账号 + 登录

**Files:**
- Create: `backend/src/main/resources/db/migration/V30__use_unit_account.sql`
- Modify: `entity/UseUnit.java`（account/passwordHash/openid）、`auth/*`（使用单位登录鉴权）
- Create: `service/UnitAccountService.java`、`controller/UnitAccountController.java`

**Interfaces:**
- Produces: `POST /unit/accounts`（管理端开通）、`POST /unit/login`（账号密码）、`POST /unit/bind-wechat`（邀请码绑定微信）。

- [ ] **Step 1: 写 V29 迁移 + 实体**：`use_unit` 加 `account`/`password_hash`/`openid`。
- [ ] **Step 2: 写失败测试**：`UnitAccountServiceTest.loginRejectsWrongPassword`（错密码 401）；`bindWechatByInviteCode`（邀请码绑定）。
- [ ] **Step 3: 跑测试确认失败** → FAIL。
- [ ] **Step 4: 实现** `UnitAccountService` + 登录鉴权（JWT 带 `use_unit` 身份）。
- [ ] **Step 5: 跑测试确认通过** → PASS。
- [ ] **Step 6: Commit**：`feat: 使用单位账号与登录`。

## Task 10: 使用单位只读接口 + 报修

**Files:**
- Create: `service/UnitPortalService.java`、`controller/UnitPortalController.java`
- Modify: `service/FaultService.java`（使用单位报修按本单位电梯）

**Interfaces:**
- Consumes: Task 9 鉴权身份（`useUnitId`）。
- Produces: `GET /unit/elevators`、`GET /unit/records`、`GET /unit/faults`、`GET /unit/pending-signs`、`POST /unit/faults`（从本单位电梯选）。

- [ ] **Step 1: 写失败测试**：`UnitPortalServiceTest.scopesToOwnUnit`（只返回本单位电梯/记录/急修，含他人单位数据时被过滤）；`reportFaultCreatesAndDispatches`（报修→急修单→派给电梯责任人）。
- [ ] **Step 2: 跑测试确认失败** → FAIL。
- [ ] **Step 3: 实现** `UnitPortalService` + `UnitPortalController` + 报修复用 `/faults`。
- [ ] **Step 4: 跑测试确认通过** → PASS。
- [ ] **Step 5: Commit**：`feat: 使用单位只读接口与报修（复用急修链路）`。

## Task 11: 维保日历投影

**Files:**
- Create: `service/MaintenanceCalendarService.java`、`controller/MaintenanceCalendarController.java`

**Interfaces:**
- Produces: `GET /maintenance-calendar`（按本人/本区域，投影未来 12 个月半月/季度/半年/年度排期）。

- [x] **Step 1: 写失败测试**：`MaintenanceCalendarServiceTest.projectsQuarterlyAndSemiannual`（从首保日起，第 90 天投影为季度 TM、第 180 天为半年 SM、第 365 天为年度 OY、其余为半月 HM）。
- [x] **Step 2: 跑测试确认失败** → FAIL。
- [x] **Step 3: 实现** 前向投影算法（逐日累加式判定，不落库）。
- [x] **Step 4: 跑测试确认通过** → PASS。
- [x] **Step 5: Commit**：`feat: 维保日历只读投影（未来12个月）`。

## Task 12: 小程序——移交与外接建档页面

**Files:**
- Create: `services/handover.js`、`services/filing.js`、`services/contract.js`
- Create: `pages/handover/init|detail|inspect|rectify|confirm`（四件套）、`pages/filing/init|detail|inspect|confirm`
- Modify: `app.json`（注册页面）

**Interfaces:**
- Consumes: Task 4—7 后端接口。

- [ ] **Step 1: 写 services**（`getHandoverList/init/submitDocs/...`，动词开头，对齐 `docs/04`）。
- [ ] **Step 2: 写页面**：移交发起/详情/查验/整改/确认、外接建档发起/资料/接管查验/交接确认（外部签字复用 `pages/common/signature`）。
- [ ] **Step 3: 根目录 lint**：`npm run lint` → 通过。
- [ ] **Step 4: 注册页面 + mock 路由同步**：`mock/server.js` 补移交/外接路由。
- [ ] **Step 5: Commit**：`feat: 小程序移交与外接建档页面`。

## Task 13: 小程序——使用单位门户 + 维保日历

**Files:**
- Modify: `services/unit.js`（补门户接口）
- Create: `pages/unit/home|elevators|records|faults|report`、`pages/calendar/index`
- Modify: `app.json`（注册 + 使用单位自定义 tabBar/首页路由）

**Interfaces:**
- Consumes: Task 10—11 后端接口。

- [ ] **Step 1: 写门户页面**：首页/电梯列表/维保记录/急修记录/待签字/报修（报修从本单位电梯选）。
- [ ] **Step 2: 写维保日历页**：展示未来 12 个月排期。
- [ ] **Step 3: 使用单位登录入口 + 按角色路由分发**。
- [ ] **Step 4: 根目录 lint** → 通过。
- [ ] **Step 5: Commit**：`feat: 使用单位门户与维保日历页面`。

## Task 14: 管理端页面

**Files:**
- Create: `admin/src/views/HandoverView.vue`、`FilingView.vue`、`ContractView.vue`、`RegionView.vue`、`UnitAccountView.vue`
- Modify: `admin/src/views/ElevatorsView.vue`、`LedgerView.vue`（区域列）、`admin/src/router/index.js`

**Interfaces:**
- Consumes: Task 1—10 管理端只读接口。

- [ ] **Step 1: 写视图**：移交单/外接建档单列表详情、合同管理、区域管理、使用单位账号管理。
- [ ] **Step 2: 电梯页/台账加区域列与 `archive_source` 列。
- [ ] **Step 3: 跑 admin 三件套**：`npm run lint && npm test && npm run build` → 通过。
- [ ] **Step 4: Commit**：`feat: 管理端移交/外接/合同/区域/使用单位账号视图`。

## Task 15: 文档同步 + 回归验收

**Files:**
- Modify: `docs/04`（A 部分接口条目）、`docs/03`（页面）、`docs/09`（管理端）、`docs/12`（待办状态）、`AGENTS.md`（§1 计数 + §10 修订记录）、`mock/server.js`

**Interfaces:**
- 无新代码接口；三处同步收尾。

- [ ] **Step 1: 三处同步接口文档**（`services/` + `mock/server.js` + `docs/04`）。
- [ ] **Step 2: 更新计数与修订记录**（AGENTS.md §10 追加 V3.4）。
- [ ] **Step 3: 全量回归**：backend `mvn test`、根 `npm run lint`、`node scripts/verify-dispatch.js`、`node scripts/verify-templates.js`。
- [ ] **Step 4: 走查小程序链路**：mock 下走「登录 → 移交发起 → 查验 → 归档 → 首保 → 签退」全链路。
- [ ] **Step 5: Commit**：`docs: 同步移交/外接建档/使用单位门户文档与计数`。
