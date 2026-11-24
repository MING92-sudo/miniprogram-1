# 客户签字归档与签退监管未知结果恢复 设计（2026-11-23）

## 意图与背景

全量审查修复序列第四阶段。阶段二（幂等跨层原子性）的 `executeExternal` 只保证键作用域与 owner 防护，明示「业务恢复由后续阶段处理」。本阶段实现两条链路的业务恢复策略：签退 → 2.6 监管上报、客户签字 → 归档。

两条链路同一病根：**本地写已成功后，外部步骤（监管 HTTP / PDF / 存储）失败或结果未知，被当成整个请求失败**——幂等键释放、用户重试被「已完成」拒绝、业务状态卡死在不可恢复的中间态。

## 现状与证据（代码实证）

### A. 签退 → 2.6 上报

- `WorkOrderService.checkout`（约 1266-1287 行）：本地事务提交（`completeCheckout` 条件更新 + 记录落库 `reportStatus=SUBMITTED` + 电梯滚动）后，事务**外**调 `reportService.attemptUpload(r)`。
- `PlatformReportService.upload`：平台成功 → `updateReportProgress(REPORTED)` 失败 → `markUnknown` 再写 `UNKNOWN` 也失败 → 抛 `OutcomeUnknownException(2002)`。
- 后果链：
  1. DB 停留 `SUBMITTED` 且带完整报文快照 → `syncLegacy`（2.8）扫描条件恰为 `reportStatus=SUBMITTED` → **自动重报** → `originalRecordId` 重复写监管平台（安全红线 §3.3 禁止任何自动重试）；
  2. 用户收到 2002 报错但签退已成功；幂等键释放后重试 → `completeCheckout` 条件更新 0 行 → 1003「工单已被其他请求签退」→ **用户卡死**；
  3. 无任何「结果待核对」可见标记，只能进库查。
- 同类卡死：`reupload` 抢占 `REUPLOADING` 后平台成功但两次本地写失败 → 停留 `REUPLOADING`，而 `reupload` 端点仅放行 `FAILED` → **永久卡死**；`syncLegacy` 的 `LEGACY_UPLOADING` 同理。

### B. 客户签字 → 归档

- `UnitRecordService.signRecord`（约 152-183 行）：条件更新（`PENDING_CLIENT_SIGNATURE→SIGNED`，先签为准）落库 → `onClientSigned` → `archive`：PDF 渲染（签名图重签时效 URL）→ `fileStorage.storeBytes` → 更新 `ARCHIVED`。
- 归档任一步失败：请求报错、**签字已生效**；幂等键释放；重试 422「记录已被签署」；记录停留 `SIGNED` 无归档。
- 既有恢复仅 `archiveView` 懒补（读时补归档）；重复归档每次 `storeBytes` 新文件，旧 `archiveFileId` 孤儿化；`batchConfirmAs` 循环内归档异常（非 BizException）会炸掉整批（前面已签的保持已签，响应整体 500）。

## 目标

1. 签退上报在任一环节崩溃/结果未知：记录必须停留在**可识别、可人工恢复**的状态；绝不让带快照的记录留在 `SUBMITTED` 被 2.8 自动扫中。
2. 待核对记录：管理端**逐条人工核对**恢复（先展示本地证据，再二选一标记）；不引入任何自动/批量/定时补偿。
3. 客户签字：签字成功即返回成功，归档失败不拖垮签字；归档可安全重入且幂等（无孤儿文件）；状态机不出现「已归档但签字缺失」。
4. 响应语义：不再因外部步骤结果未知而整体报错；显式状态随响应返回并被幂等完成写固化，重放返回同一状态。

## 非目标

- 不引入对 `originalRecordId` 的自动重试、批量重试、定时补偿（红线，含 reconcile 也不得触碰平台）。
- 不改 2.6 报文字段、快照冻结、`ReportPayloadValidator`。
- 不改 rework（`DRAFT`）口径：复核退回整改新版本保留原 `originalRecordId` 且绝不上报。
- 不做存量卡死记录的批量数据修复（提供核对入口后逐条人工处理）。
- 不改动签到/派单/动态码等业务不变量链路。

## 约束

- 监管 HTTP / PDF / 存储不得纳入数据库长事务（保持事务外执行）。
- 核对动作只改本地状态、逐条触发、触发前展示本地证据（上报快照 digest、`reg_upload_log` 应答记录、`originalRecordId`）。
- `ARCHIVED` 必经 `SIGNED`：归档前置断言 `confirmStatus=CONFIRMED && lifecycleStatus=SIGNED`。
- Flyway 只追加；本设计零迁移（`report_status` 为 VARCHAR 无枚举约束，V1/V2 已证）。
- 接口两处同步：`services/` + `mock/server.js`；管理端 API 走 `admin/src/api/`。
- 测试不得触发真实平台写接口（2.3/2.4/2.6/2.8），用 mock 平台客户端/故障注入。

## 方案（用户已选定：签退 A + 归档 A）

### A1. 签退侧：前置「上报中」抢占态

新增状态 `REPORTING`（管理端 `RecordsView.vue` 标签 map 已有此键，文案「上报中」直接复用）。状态机：

```
SUBMITTED --(configured, 调平台前原子抢占)--> REPORTING
REPORTING --平台成功+本地写成功--> REPORTED
REPORTING --平台确定失败--> FAILED            （现有 reupload 逐条人工重报，不变）
REPORTING --平台成功+本地写失败--> UNKNOWN    （待核对；markUnknown 语义保留）
REPORTING --崩溃/两次本地写均失败--> 停留 REPORTING （待核对）
SUBMITTED --未配置平台--> 保持 SUBMITTED      （2.8 存量迁移候选，语义不变）
```

- `MaintainRecordMapper` 新增 `beginUpload(id)`：条件更新 `SUBMITTED→REPORTING`，影响行数 0 则视为已被并发请求处理（抛 1003）。
- `attemptUpload`：未配置平台 → 直接返回 `SUBMITTED`（不动库，现状保留）；已配置 → 先 `beginUpload` 抢占再调平台。
- `OutcomeUnknownException` **不再抛向控制器**：`attemptUpload` 只返回状态字符串（`REPORTED/FAILED/UNKNOWN/REPORTING`），checkout 正常返回含该状态的响应 → 幂等完成写固化 → 重放同状态。小程序按状态提示「上报结果待核对，请勿重复操作」。
- `reupload`：`REUPLOADING` 内平台成功但本地写失败 → 写 `UNKNOWN`，写不动则停留 `REUPLOADING`（待核对）；`LEGACY_UPLOADING` 同理。所有「停留态」由 A2 收口。
- `syncLegacy` 扫描条件 `SUBMITTED` 不变 → `REPORTING/UNKNOWN` 天然排除在自动迁移之外（红线自证）。

### A2. 管理端逐条人工核对（唯一恢复入口）

- 新端点 `POST /platform/records/{id}/reconcile`，body `{outcome: 'REPORTED'|'FAILED', note}`：
  - 前置：`reportStatus ∈ {REPORTING, REUPLOADING, LEGACY_UPLOADING, UNKNOWN}` 且链末端（`hasNextVersion` 拒绝旧快照）；角色 `ADMIN`/`PLATFORM_ADMIN`（沿用平台管理权限点）。
  - `outcome=REPORTED`：操作者已在平台控制台核实该 `originalRecordId` 存在 → 条件更新为 `REPORTED`（`retryCount` 不动），写 `reg_upload_log`（`action=RECONCILE`, `status=SUCCESS`, note 脱敏截断）。
  - `outcome=FAILED`：核实平台无此记录 → 条件更新为 `FAILED`（之后可走现有 `reupload` 逐条人工重报），写日志（`action=RECONCILE`, `status=MANUAL_FAILED`）。
  - 幂等：`executeDb` + X-Idempotency-Key；条件更新 0 行 → 1003「状态已变化」。
  - **reconcile 不调用 `PlatformClient` 任何方法**；无批量入参；无定时任务。
- 证据展示（触发前可见）：上报异常清单（`FailedRecordsView`）扩充纳入待核对集合（现状 `UNKNOWN` 被刻意排除，需改过滤与测试），行内/抽屉展示：`originalRecordId`、报文快照 digest、`reg_upload_log` 最近条目（现有 `GET /reg/upload-logs`）、`retryCount`；操作者据此到平台控制台人工核实后点「标记已上报 / 标记未上报」。

### A3. 签字/归档解耦

- `signRecord`：条件更新 `SIGNED` 成功后，`onClientSigned` 包 try/catch——归档失败记 warn（含 recordId、异常类型），记录停留 `SIGNED`（=待归档），响应 `ok:true` 附带 `archived:false`；幂等完成写固化该载荷，重放同状态（不再 422）。
- `batchConfirmAs`：归档异常在 `signRecord` 内部消化，不再炸批；每条记录签字门禁不变。
- 归档幂等与可重入：
  - `archive()` 前置断言 `confirmStatus=CONFIRMED && lifecycleStatus=SIGNED`（已 `ARCHIVED` 且 `archiveFileId` 非空 → 直接返回，幂等短路）；
  - `SIGNED` 重归档：渲染 + 存储新 PDF → **条件更新**（`eq lifecycleStatus=SIGNED`）；孤儿清理规则见「Builder check 复核裁定」第 2 条（落败方删自己刚存的文件；崩溃遗留无引用文件接受不扫）；
  - 既有 `archiveView` 懒补保留（`SIGNED` 读时补归档），是员工侧天然重入入口。
- 展示：`SIGNED` 且 `archiveFileId` 为空 → 「待归档」标签（admin `labels`/小程序 `constants` 同步）；`archiveView` 补归档后转 `ARCHIVED`。

### 放弃的备选

- 签退 B（仅本地 DB 写进程内重试）：不崩溃安全，`SUBMITTED` 卡死根因仍在。
- 归档 B（同步归档 + 部分成功返回）：不解孤儿文件、不解批量炸批。

## 改动清单（文件所有权声明）

**Backend**
- `service/WorkOrderService.java`（checkout 抢占与响应）
- `service/PlatformReportService.java`（attemptUpload/reupload/syncLegacy 状态机，OutcomeUnknown 收口）
- `service/UnitRecordService.java`（signRecord 归档解耦 + archived 标志）
- `service/RecordLifecycleService.java`（archive 幂等 + 前置断言 + 孤儿清理）
- `controller/PlatformController.java`（reconcile 端点；`/platform/sync` 响应增加 stuck 计数）
- `mapper/MaintainRecordMapper.java`（beginUpload、reconcile 条件更新）
- `service/PlatformReportService.java` 经 `WorkOrderMapper` 同步工单 `report_status`（记录带 `workOrderId` 时，见裁定 1）
- 测试（同包）：`PlatformReportServiceTest`、`WorkOrderService*` checkout 用例、`UnitRecordService*` 签字用例、`RecordLifecycleService*` 归档用例、reconcile 新用例

**Mock / 小程序**
- `mock/server.js`（checkout 状态、签字 archived、异常清单含待核对、reconcile 路由、sync stuck 计数）
- `mock/data.js`（uploadStatus 映射镜像：在途/停留态 → UNKNOWN）
- `services/`（reconcile 对应方法，若小程序侧需要；否则仅 admin）
- `constants/index.js`（REPORTING/待核对/待归档/请勿重复操作 文案）
- `scripts/verify-*.js`（新增契约校验，CI 加行）

**Admin**
- `admin/src/api/report.js`（reconcile）
- `admin/src/views/FailedRecordsView.vue`（待核对集合 + 证据 + 核对按钮）
- `admin/src/ui/labels.js`、`admin/src/constants/index.js`（状态文案）
- 对应 `*.test.js`

## 验证

- `mvn test`：新增——平台成功+本地写失败 → 停留 `REPORTING` 且 `syncLegacy` 不扫；`beginUpload` 并发抢占；reconcile 二态转换/门禁/链末端/日志；签字归档失败 → `ok+archived:false` 且幂等重放同状态；重归档幂等无孤儿；归档前置断言。
- `node scripts/verify-*.js`：mock 契约（签退待核对、签字 archived、reconcile）。
- admin：`npm run lint && npm test && npm run build`（Windows 注意 `TMP/TEMP` 指 `admin/.tmp-build`）。
- 根 `npm run lint`。
- 禁止触发真实平台写接口；故障注入走测试替身。

## Builder check 复核裁定（2026-11-23，四项均为设计方裁定）

1. **工单标签同步**：`work_order.report_status` 仅在 checkout 末尾写一次，reconcile/停留态会使小程序工单列表标签永久失真（既有 reupload 成功也有同样遗留）。裁定：reconcile 与 reupload 在记录状态落库后，若记录带 `workOrderId` 则同步 `updateReportStatus`（无 `workOrderId` 的历史记录跳过）；checkout 路径本就先写记录后写工单，崩溃窗口由 reconcile 收口。代价：工单与记录状态短暂不一致窗口存在，但不会出现**永久**错误标签。
2. **归档孤儿精确规则**：`archiveFileId` 与 `ARCHIVED` 同一次原子更新写入，`SIGNED` 记录不存在"旧 archiveFileId"可删。真实孤儿只两种——(a) 并发归档的条件更新落败方刚存入的文件：落败方（0 行）best-effort 删除**自己刚存的**那个文件（严禁读库删 winner 的文件）；(b) storeBytes 与库更新之间崩溃遗留的无引用文件：接受为不可避免，不做清理扫描（与禁止自动补偿红线一致），顺序重归档被幂等短路挡住不会发生。
3. **syncLegacy 中途未知结果**：改为跳过该条并继续本批（不再中断整批外抛），响应在既有计数上增加 `stuck` 数量与「见异常清单」提示；`/platform/sync` 为人工触发端点（非定时），继续语义不形成自动重试。
4. **uploadStatus 映射收口**：`UnitRecordService.uploadStatus`（及 mock 镜像 `mock/data.js`）将 `REPORTING/REUPLOADING/LEGACY_UPLOADING` 统一映射为 `UNKNOWN`（待核对口径，与管理端一致），不再落入 `PENDING`（待上报）——否则工人端永远看到「待上报」，违背可见性目标。

## 交给实现者的决定（已在设计中裁定）

- 新状态名 `REPORTING`（复用 admin 既有标签；与 `REUPLOADING/LEGACY_UPLOADING` 同族但语义独立）。
- `SUBMITTED` 语义保持「未配置平台的待迁移」不动，避免破坏 2.8 存量链路与管理端 KPI 计数（`AdminService:93`）。
- reconcile 落 `PlatformController`（与 `reupload` 并列，同权限域）。
- 待核对与 FAILED 同列表展示、标签区分（复用现有异常清单视图，不新开页面）。
