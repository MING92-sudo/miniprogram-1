# 博威无纸化小程序重构 M0 映射基线

> V1.0 · 2026-11-15 · 依据规格 2026-11-15-miniapp-redesign-design.md · 守卫：scripts/verify-miniapp-m0.js

## 1 维保记录状态机映射

| 旧态/节点 | 代码出处 | 蓝图 §6.2 对应 | 新实现口径 |
|---|---|---|---|
| PENDING | WorkOrderService.java:321 | 计划生成→接单 | 不变；接单置 PROCESSING |
| PROCESSING | WorkOrderService.java:375 | 作业中（点检/拍照/实测） | 不变；签到/动态码/清单合规主干逐字不动 |
| 主检签名 | POST /work-orders/{id}/signature（V23） | 主检签名提交→待配合人签字 | 不变；新增配合退回端点 signature-refuse（无上报副作用） |
| 配合签名 | POST /work-orders/{id}/signature（V23） | 配合人核对签名→待复核/待使用单位签字 | 不变；各签各的 |
| DONE | WorkOrderService.java:735 | 双签完成=签退→生命周期入口 | 签退即冻结快照+2.6 即报（D2）；DONE 后叠加 lifecycle_status |
| 新增 PENDING_REVIEW | — | 待复核 | sys_config 键 record_review_enabled，默认关=免复核直达；主管 record:review |
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

（Task 3 写入）

## 4 路由对照（49 页）

（Task 4 写入）

## 5 权限码覆盖（106 唯一）

（Task 5 写入）

## 6 原型资产提取清单

（Task 6 写入）