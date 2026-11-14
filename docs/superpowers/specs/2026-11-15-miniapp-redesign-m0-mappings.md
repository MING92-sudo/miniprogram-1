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

（Task 2 写入）

## 3 急修/报修状态机映射

（Task 3 写入）

## 4 路由对照（49 页）

（Task 4 写入）

## 5 权限码覆盖（106 唯一）

（Task 5 写入）

## 6 原型资产提取清单

（Task 6 写入）