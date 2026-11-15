# 博威无纸化小程序重构 M0 映射基线

> V1.0 · 2026-11-15 · 依据规格 2026-11-15-miniapp-redesign-design.md · 守卫：scripts/verify-miniapp-m0.js
> 门禁（2026-11-15，结构等价取证）：11 检查全 missingCount=0 —— record-states 3 / handover-states 6 / handover-baseline 6 / fault-states 3 / fault-drift 3 / routes-old 40 / routes-new 49 / routes-count 49 / perm-codes 106 / perm-count 106 / assets 3；根 lint 绿。图标/字体真实绘制验收留 M2。

## 1 维保记录状态机映射

| 旧态/节点 | 代码出处 | 蓝图 §6.2 对应 | 新实现口径 |
|---|---|---|---|
| PENDING | WorkOrderService.java:321 | 计划生成→接单 | 不变；接单置 PROCESSING |
| PROCESSING | WorkOrderService.java:375 | 作业中（点检/拍照/实测） | 不变；签到/动态码/清单合规主干逐字不动 |
| 主检签名 | POST /work-orders/{id}/signature（V23） | 主检签名提交→待配合人签字 | 不变；新增配合退回端点 signature-refuse（无上报副作用） |
| 配合签名 | POST /work-orders/{id}/signature（V23） | 配合人核对签名→待复核/待使用单位签字 | 不变；各签各的 |
| DONE | WorkOrderService.java:735 | 双签完成=签退→生命周期入口 | 签退即冻结快照+2.6 即报（D2）；DONE 后叠加 lifecycle_status |
| 新增 PENDING_REVIEW | — | 待复核 | sys_config 键 record_review_enabled，默认关=免复核直达；复核动作在管理端（record:review，蓝图 §12 ①），小程序仅展示状态 |
| 新增 PENDING_CLIENT_SIGN | — | 待使用单位签字 | sign-request 发起（主检/主管，门控双签齐） |
| 新增 PENDING_CLIENT_SIGNATURE | — | 待使用单位签署 | 一次性签署任务 taskId+token（复用 sign-view/confirm-by-token） |
| 新增 SIGNED | — | 已签署 | 触发自动归档 |
| 新增 ARCHIVED | — | 已归档 | PDF+SHA-256（record:archive 系统/主管）；不触发平台调用 |
| 新增 REFUSED_RETURNED | — | 拒签→待整改 | 拒签必填原因，退回维保人员重新走签字链路 |
| 修订 | 管理端 amend-v2（V38） | 已归档→修订 | 复用；经理+平台双人；新版本+新哈希；不重报 2.6（P-5） |

## 2 移交状态机映射

| 旧态/节点 | 代码出处 | 蓝图 §6.1 对应 | 新实现口径 |
|---|---|---|---|
| INIT | HandoverService.java:48 | 草稿 | 不变；create 页（屏 09） |
| DOC_SUBMITTING | HandoverService.java:49 | 草稿→待安装部审核（资料提交） | upload 页 D1–D20（3 强制卡点）；齐全提交进入新增审核态 |
| 新增 PENDING_INST_REVIEW | — | 待安装部审核 | review 页 INST_MGR 审核签章/退回（原因+期限）；M1 新增态+端点 |
| INSPECTING | HandoverService.java:50 | 待维保接收→验收中 | 接收指派（MAINT_SUP handover:receive）后进入；inspect 74 项 G01–G13 逐项留证 |
| RECTIFYING | HandoverService.java:51 | 退回整改 | 1:1 item_no 整改不可增删；逐条复验全 PASS 才回待确认（C-12） |
| PENDING_CONFIRM | HandoverService.java:52 | 待双方签字 | 判定 QUALIFIED/CONDITIONAL 后进入；sign 页五节点；☆NA 经理审批 |
| ARCHIVED | HandoverService.java:53 | 已生效→已归档 | 生效→台账在保+首保计划（沿用现有口径）；平台 2.3/2.4 沿用不新增 |
| 判定 DISQUALIFIED | HandoverChecklistService 7.2/7.3 | 退回整改 | 重要项任一不合格/一般项>8；CONDITIONAL 三条件齐备 |
| 容缺分支 | handover_doc 缺件标记 | 容缺受理 | 缺件补传完成前不得进入已生效 |
| 新增 VOIDED | — | 任意未生效→作废 | 安装主管+维保经理+平台审计；原因必填；M1 新增端点 |
| filing 四页 | pages/filing/*（W2/V29） | 蓝图无独立路由 | 保留扩展迁 pkg-handover/filing-*；archive_source=2+首保+3 日不变 |

## 3 急修/报修状态机映射

| 旧态/节点 | 代码出处 | 蓝图 §6.3 对应 | 新实现口径 |
|---|---|---|---|
| OPEN | DirectoryService.java:181 | 待接单 | 抢单（order:grab 本区域）/派单（order:dispatch 主管/经理）→ASSIGNED |
| ASSIGNED | DirectoryService.java:209 | 已接单 | 新增到场打卡节点（order:arrive GPS+时间戳）→处置中 |
| PROCESSING | DirectoryService（M1 落地） | 处置中 | 到场后进入；维修过程字段沿用 PUT /faults/{id} |
| PENDING_CONFIRM | DirectoryService（M1 落地） | 待使用单位确认 | 提交急修单（order:submit）后进入；CLIENT_SIGNER 确认（管理员须兼任） |
| CLOSED | DirectoryService.java:414 | 已闭环 | 确认后系统闭环（order:close 系统/主管）；维保人员只读；评价入口开放 |
| REFUSED | DirectoryService（M1 落地） | 待整改 | 拒绝必填原因，退回处置 |
| CANCELLED | DirectoryService（M1 落地） | 已取消 | 接单前单位管理员/接单后主管；原因必填（order:cancel） |
| 升级/改派 | 新增 SLA 任务 | 改派/升级 | order:escalate 阈值 config:manage；超时通知上级 |
| 到场时间现状 | DirectoryService.java:224 | 到场打卡 | 现以最早签到推导 arrivedAt；新增显式打卡节点后以打卡为准，签到推导兜底留痕 |
| 评价 | 无端点 | 已闭环→待评价 | 新增 POST /faults/{id}/evaluate（client:evaluate，1–5 星+文字） |

## 4 路由对照（50 页）

> 旧页存在性核实（2026-11-15 实测）：app.json 40 页逐页 fs.existsSync(p+'.js')，missing=[]。
> 守卫 routes-old/new 按表格单元格精确解析（非子串匹配），防旧路径误匹配新路由前缀。

| 新路由 | 旧页来源 | 批次 |
|---|---|---|
| pages/login/index | pages/login/index | M2 |
| pages/login/agreement | pages/login/agreement | M2 |
| pages/login/forgot | 新建（V2.19 忘记密码，无旧页等价） | V2.19 |
| pages/install/home/index | 新建 | M2 |
| pages/maint/home/index | pages/home/index | M2 |
| pages/client/home/index | pages/unit/home | M2 |
| pages/order/list/index | pages/fault/list | M3 |
| pages/order/detail/index | pages/fault/detail | M3 |
| pages/order/report/index | pages/fault/report + pages/unit/report | M3 |
| pages/elevator/list/index | pages/elevator/list + pages/unit/elevators | M2 |
| pages/elevator/detail/index | pages/elevator/detail | M2 |
| pages/profile/index | pages/mine/index + pages/unit/mine/index | M2 |
| pages/profile/password | pages/mine/password | M2 |
| pages/403/index | 新建 | M2 |
| pages/message/index | pages/message/index | M2 |
| pages/offline/index | pages/mine/offline | M2 |
| pages/common/watermark-camera | pages/common/watermark-camera | M2 |
| pages/common/signature | pages/common/signature | M2 |
| pkg-handover/pages/list/index | pages/handover/list | M4 |
| pkg-handover/pages/create/index | pages/handover/init | M4 |
| pkg-handover/pages/detail/index | pages/handover/detail | M4 |
| pkg-handover/pages/inspect/index | pages/handover/inspect | M4 |
| pkg-handover/pages/rectify/index | pages/handover/rectify | M4 |
| pkg-handover/pages/sign/index | pages/handover/confirm | M4 |
| pkg-handover/pages/upload/index | 新建 | M4 |
| pkg-handover/pages/review/index | 新建 | M4 |
| pkg-handover/pages/filing-init/index | pages/filing/init | M4 |
| pkg-handover/pages/filing-detail/index | pages/filing/detail | M4 |
| pkg-handover/pages/filing-inspect/index | pages/filing/inspect | M4 |
| pkg-handover/pages/filing-confirm/index | pages/filing/confirm | M4 |
| pkg-maintenance/pages/orders/index | pages/order/list | M3 |
| pkg-maintenance/pages/task/index | pages/order/detail + pages/order/checklist + pages/order/item-form | M3 |
| pkg-maintenance/pages/checkin/index | pages/order/checkin | M3 |
| pkg-maintenance/pages/dynamic-code/index | pages/order/dynamic-code | M3 |
| pkg-maintenance/pages/checkout/index | pages/order/checkout | M3 |
| pkg-maintenance/pages/record-detail/index | pages/order/detail + pages/order/record-preview | M3 |
| pkg-maintenance/pages/plan/index | pages/calendar/index | M3 |
| pkg-maintenance/pages/record-sign/index | 新建（包装 pages/common/signature） | M3 |
| pkg-maintenance/pages/record-archive/index | 新建 | M3 |
| pkg-client/pages/todo/index | pages/unit/records | M5 |
| pkg-client/pages/sign/index | pages/unit/sign | M5 |
| pkg-client/pages/orders/index | pages/unit/faults | M5 |
| pkg-client/pages/evaluate/index | 新建 | M5 |
| pkg-admin/pages/projects/index | 新建 | M6 |
| pkg-admin/pages/contracts/index | 新建 | M6 |
| pkg-admin/pages/users/index | 新建 | M6 |
| pkg-admin/pages/roles/index | 新建 | M6 |
| pkg-admin/pages/stats/index | 新建 | M6 |
| pkg-admin/pages/audit/index | 新建 | M6 |
| pkg-admin/pages/settings/index | 新建 | M6 |

## 5 权限码覆盖（106 唯一）

> 口径：106 = 蓝图 §12 71 码（28 路由 + 21 管理端 + 19 页内 + 3 系统）+ 冻结旧码 24 + v2 补充 11。
> 承载 token：路由 = 小程序路由直达；② = 页内动作；① = 管理端承接；③ = 系统自动；冻结 = 旧码桥接（LegacyCapabilityTranslator，RbacMatrixTest/CompatTest 锁定）。
> 语义反转：旧 order:read/write = 维保工单；新 order:view_pool/grab/… = 急修；两系分行不合并。

| 权限码 | 承载 | 路由/端点 | 允许角色 |
|---|---|---|---|
| handover:view | 路由 | pkg-handover/pages/list/index + detail/index | 各角色按范围；单位生效后只读 |
| handover:create | 路由 | pkg-handover/pages/create/index | INST_PM |
| handover:edit | ② | create/index 草稿编辑 | INST_PM；INST_CLERK 仅资料字段 |
| handover:doc_upload | 路由 | pkg-handover/pages/upload/index | INST_CLERK · INST_PM |
| handover:doc_delete | ② | upload/index 行删除 | INST_CLERK/INST_PM 发起，INST_MGR 确认 |
| handover:submit | ② | create/index 提交审核 | INST_PM |
| handover:review | 路由 | pkg-handover/pages/review/index | INST_MGR |
| handover:review_return | ② | review/index 退回 | INST_MGR |
| handover:receive | ① | 管理端移交管理「接收并指派」 | MAINT_SUP |
| handover:inspect | 路由 | pkg-handover/pages/inspect/index | 被指派 MAINT_TECH |
| handover:rectify | 路由 | pkg-handover/pages/rectify/index | INST_PM · INST_CLERK |
| handover:sign_install | 路由 | pkg-handover/pages/sign/index | INST_MGR |
| handover:sign_maint | 路由 | pkg-handover/pages/sign/index | MAINT_SUP · MAINT_MGR |
| handover:void | ① | 管理端移交管理「作废」 | INST_MGR→MAINT_MGR→平台审计 |
| handover:export | ① | 管理端移交管理导出 | INST_MGR/MAINT_SUP/MAINT_MGR/平台 |
| handover:notify_client | ① | 管理端「知会使用单位」 | MAINT_SUP |
| project:view | 路由 | pkg-admin/pages/projects/index | 按范围；INST_CLERK△ 仅移交内项目 |
| project:edit | ① | 管理端项目台账（小程序屏 03 第一档禁用） | MAINT_MGR · 平台 |
| contract:view | 路由 | pkg-admin/pages/contracts/index | MAINT_SUP△ 期与台量；经理/平台/单位△ |
| contract:edit | ① | 管理端合同管理 | MAINT_MGR · 平台 |
| elevator:view | 路由 | pages/elevator/list/index + detail/index | 全部角色△ 按范围 |
| elevator:edit | ① | 管理端电梯台账 | MAINT_SUP · 平台 |
| elevator:qr | ② | elevator/detail/index 设备码/扫码 | MAINT_TECH/MAINT_SUP/CLIENT_ADMIN |
| elevator:transfer | ① | 管理端移装/停用/报废 | MAINT_MGR+平台双人 |
| elevator:export | ② | elevator/detail/index 导出台账（屏 08 第一档禁用） | MAINT_MGR/平台；SUP△ 本项目；CLIENT_ADMIN△ 本单位 |
| plan:view | 路由 | pkg-maintenance/pages/plan/index | 按范围；单位仅本单位 |
| plan:generate | ① | 管理端维保计划生成 | MAINT_SUP · MAINT_MGR |
| task:accept | 路由 | pkg-maintenance/pages/task/index | MAINT_TECH 主检 |
| task:assign_partner | ② | task/index 指定配合人 | MAINT_SUP · 主检 |
| checklist:fill | ② | task/index 点检填写 | 主检 · 配合人 |
| record:view | 路由 | pkg-maintenance/pages/record-detail/index | 按范围；单位只读 |
| record:submit | ② | task/index + record-sign/index 主检签名提交 | 主检 |
| record:sign_tech | 路由 | pkg-maintenance/pages/record-sign/index | 两名不同账号 MAINT_TECH |
| record:review | ① | 管理端记录单复核 | MAINT_SUP · MAINT_MGR |
| record:sign_request | ② | record-detail/index 发起使用单位签字 | 主检 · MAINT_SUP |
| record:sign | 路由 | pkg-client/pages/sign/index + todo/index | CLIENT_SIGNER |
| record:refuse | ② | sign/index 拒绝签署 | CLIENT_SIGNER |
| record:archive | ③ | 状态机已签署→已归档自动 | 系统/MAINT_SUP |
| record:amend | ① | 管理端归档后修订 | MAINT_MGR→平台双人 |
| record:export | 路由 | pkg-maintenance/pages/record-archive/index | MAINT_SUP/经理/平台；CLIENT_ADMIN△ |
| order:create | 路由 | pages/order/report/index | CLIENT_ADMIN · CLIENT_SIGNER |
| order:create_proxy | ① | 管理端代报修（V38 已有端点） | MAINT_SUP |
| order:view_pool | 路由 | pages/order/list/index + detail/index | 维保侧 |
| order:view_own | 路由 | pages/order/detail/index + pkg-client/pages/orders/index | CLIENT_ADMIN · CLIENT_SIGNER |
| order:grab | ② | order/list/index 抢单 | MAINT_TECH 本区域 |
| order:dispatch | ① | 管理端工单调度派单 | MAINT_SUP · MAINT_MGR |
| order:arrive | ② | order/detail/index 到场打卡 | 被指派 MAINT_TECH |
| order:process | ② | order/detail/index 处置 | 被指派 MAINT_TECH |
| order:submit | ② | order/detail/index 提交急修单 | MAINT_TECH |
| order:confirm | 路由 | pkg-client/pages/todo/index | CLIENT_SIGNER（管理员须兼任） |
| order:close | ③ | 状态机确认后自动闭环 | 系统/MAINT_SUP |
| order:cancel | ② | order/detail/index + client/orders/index 取消 | CLIENT_ADMIN 接单前；MAINT_SUP 接单后 |
| order:escalate | ③ | SLA 超时自动升级 | 系统/MAINT_MGR |
| order:export | ① | 管理端工单调度导出 | MAINT_SUP/经理/平台；CLIENT_ADMIN△ |
| client:invite | ① | 管理端授权管理邀请码 | CLIENT_ADMIN · MAINT_SUP 限本单位 |
| client:user_manage | ① | 管理端账号管理 | CLIENT_ADMIN 限非管理角色 |
| client:elevator_view | ② | elevator/list/index ORG_FLAT 过滤 | CLIENT_ADMIN · CLIENT_SIGNER |
| client:report | ② | client/home/index 一键报修（同 order:create 表单） | CLIENT_ADMIN · CLIENT_SIGNER |
| client:sign | 路由 | pkg-client/pages/sign/index | CLIENT_SIGNER |
| client:evaluate | 路由 | pkg-client/pages/evaluate/index | CLIENT_ADMIN · CLIENT_SIGNER |
| client:download | ② | sign/index + record-archive/index 下载本单位单据 | CLIENT_ADMIN · CLIENT_SIGNER |
| user:view | 路由 | pkg-admin/pages/users/index | 按范围；手机号脱敏 |
| user:create | ① | 管理端账号管理开通 | 各单位管理员限本单位 |
| user:disable | ① | 管理端停用/离职转交 | 同 user:create |
| role:assign | 路由 | pkg-admin/pages/roles/index | 平台；单位管理员限非管理角色 |
| invite:manage | ① | 管理端邀请码生成/撤回 | INST_MGR/MAINT_SUP/MAINT_MGR/CLIENT_ADMIN/平台 |
| stats:view | 路由 | pkg-admin/pages/stats/index | 按范围聚合 |
| stats:export | ① | 管理端统计报表导出 | MAINT_MGR · 平台；其他需审批 |
| audit:view | 路由 | pkg-admin/pages/audit/index | 平台；CLIENT_ADMIN△ 本单位 |
| config:manage | 路由 | pkg-admin/pages/settings/index | 平台 |
| data:export_sensitive | ① | 管理端敏感导出审批 | 平台+审批 |
| client:edit | ① | 管理端使用单位信息编辑 | CLIENT_ADMIN 限本单位 · 平台 |
| filing:view | 路由 | pkg-handover/pages/filing-detail/index | 外接建档相关角色按范围 |
| platform:sync | ③ | 平台同步（2.3/2.4/2.6 链路） | 系统 |
| platform:worker_register | ① | 管理端平台登记人员 | MAINT_SUP · 平台 |
| platform:worker_view | ① | 管理端平台登记查看 | MAINT_SUP · 平台 |
| record:spotcheck | ① | 管理端记录抽查 | MAINT_QUALITY · MAINT_MGR |
| region:view | ① | 管理端区域主数据读 | ADMIN · 平台 |
| report:view | ① | 管理端上报日志 | MAINT_SUP/经理 · 平台 |
| template:toggle | ① | 管理端模板启停 | MAINT_QUALITY · MAINT_MGR |
| template:view | ① | 管理端模板查看 | MAINT_SUP/质量/经理 |
| user:reset_password | ① | 管理端账号重置密码 | 各单位管理员限本单位 · 平台 |
| console:read | 冻结·桥接 | 管理端（adminConsole 门控） | 旧角色兼容集 |
| console:write | 冻结·桥接 | 管理端粗粒度写档 | 旧 ADMIN/SYS_ADMIN |
| order:read | 冻结·桥接 | 管理端工单监控（旧=维保工单语义） | 旧角色兼容集 |
| order:write | 冻结·桥接 | 管理端工单写（旧=维保工单语义） | 旧 LEADER/ADMIN |
| fault:read | 冻结·桥接 | 管理端急修单读 | 旧角色兼容集 |
| fault:write | 冻结·桥接 | 管理端急修单写 | 旧 LEADER/ADMIN |
| record:read | 冻结·桥接 | 管理端记录读 | 旧角色兼容集 |
| report:retry | 冻结·桥接 | 管理端上报异常逐条重报 | 旧 ADMIN（质量不可重报） |
| plan:write | 冻结·桥接 | 管理端计划调度 | 旧 LEADER 本组/ADMIN |
| template:write | 冻结·桥接 | 管理端模板管理 | 旧 ADMIN/质量启停 |
| ledger:read | 冻结·桥接 | 管理端电梯台账读 | 旧角色兼容集 |
| ledger:write | 冻结·桥接 | 管理端电梯台账写 | 旧 ADMIN |
| employee:read | 冻结·桥接 | 管理端人员档案读 | 旧角色兼容集 |
| employee:write | 冻结·桥接 | 管理端人员档案写 | 旧 ADMIN |
| contract:read | 冻结·桥接 | 管理端合同读 | 旧角色兼容集 |
| contract:write | 冻结·桥接 | 管理端合同写 | 旧 ADMIN |
| useunit:read | 冻结·桥接 | 管理端使用单位档案读 | 旧角色兼容集 |
| useunit:write | 冻结·桥接 | 管理端使用单位档案写 | 旧 ADMIN |
| handover:read | 冻结·桥接 | 管理端移交读（桥接 handover:view） | 旧角色兼容集 |
| handover:write | 冻结·桥接 | 管理端移交写 | 旧 ADMIN |
| filing:read | 冻结·桥接 | 管理端外接建档读（桥接 filing:view） | 旧角色兼容集 |
| filing:write | 冻结·桥接 | 管理端外接建档写 | 旧 ADMIN |
| region:write | 冻结·桥接 | 管理端区域主数据写 | 旧 ADMIN |
| account:manage | 冻结·桥接 | 管理端账号启停/重置 | 旧 ADMIN/SYS_ADMIN |

## 6 原型资产提取清单

> 取证类型：结构等价（计数/字节/缺口 JSON）。图标与字体的真实绘制验收留 M2 模拟器/真机。

**色彩令牌**：引用规格 §2.1（不重复维护）。

**公司 logo**：assets/brand/logo.jpg（41141 字节，450×450）。圆形徽标 bbox 实测 ≈ (55,20)–(395,335)；BOWEI 字母区 y≈360–430 **不渲染上页**。M2 渲染口径：CSS background-image(data URI) + background-size:100% auto + 视高 = 宽 × 335/450 ≈ 0.744，仅取圆形部分。

**图标**：原型 64 个 FA Free 6.5.2 symbol → scripts/asset-build/svg/（64 件，入库）→ assets/icons/icons.wxss（14889 字节；bwicons woff2 7256 字节 base64 内嵌；码位 0xF101+排序索引；类名 .i-xxx 与原型 #i-xxx 逐字一致）。许可：图标 CC BY 4.0，署名落「我的」页中文可见文本。

**显示字**：assets/fonts/zcool-proto-subset.woff2（原型 75 字子集存档，20336 字节）；assets/fonts/zcool-subset.ttf（扩字子集 135 字，84596 字节，覆盖「博威无纸化」及全部标题用字）；zcool-gap.json = {required:135, missing:[]}。OFL 1.1 无保留名，子集化沿用原名合规。M2 以 @font-face base64 内嵌（TTF 可直接用，需要时转 woff2）。

**构建管线**（scripts/asset-build/，隔离依赖不污染根）：extract-symbols.js（原型→svg）/ build-icons.js（svgicons2svgfont→svg2ttf→ttf2woff2 流式管线，绕过 fantasticon 的 glob Windows 缺陷）/ extract-zcool.js（原型子集存档+required.txt）/ subset-zcool.js（subset-font/harfbuzz WASM 扩字，targetFormat=truetype）/ check-zcool-gap.js（opentype.js cmap 缺口检测）。OFL 全字取材于 scripts/tmp/（gitignored，构建期下载，交付物零外链）。

**守卫**：verify-miniapp-m0.js assets 检查（logo 存在/64 字形/缺口为空）。
## 7 修订记录

| 日期 | 修订 |
|---|---|
| 2026-11-15 | M2 裁决 R1：verify-miniapp-m0.js routes-old 检查改为 oldCol ∪ newCol 过渡并集（app.json 新旧页并存）；M6 删旧页后收紧为 newCol 且计数 49。新增 scripts/verify-miniapp-routes.js 10 检查承接路由/菜单/导航守卫。 |
