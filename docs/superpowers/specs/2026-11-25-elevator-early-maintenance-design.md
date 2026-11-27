# 电梯「提前保养」手动派单 设计

## 意图
管理员/维保经理/主管需要在某台电梯的保养到期日之前，手动触发它进入区域抢单池，让维保员提前保养。当前自动派单只在「到期前 1 天」才进池（`DispatchService.maybeDue`），无法提前；区域为空（不限区域）不影响进池，问题纯粹在派单时机。

## 方案
复用后端已有的 `POST /elevators/{id}/dispatch`（`DispatchService.dispatchNow`）——它不看到期日，直接生成一张 `PENDING` 池子工单。前端把已存在但从未被任何页面引用的 `dispatchElevator(id)`（`admin/src/api/archive.js`）挂到管理端按钮上。零新逻辑、零新框架。

## 变更
1. **后端** `DispatchService.dispatchNow`：补 `@RequirePerm("order:dispatch")`。当前无注解走 `CONSOLE_WRITE` 兜底（仅平台管理员）；改后经 `MultiRoleGate` 校验三态矩阵，维保经理（MAINT_MGR）+ 维保主管（MAINT_SUP）+ 平台管理员（PLATFORM_ADMIN，经 PA 全码 Y 兜底）可调。
2. **管理端电梯台账列表** `ElevatorsView.vue`：非停用电梯行内加「提前保养」按钮（`PermButton code="order:dispatch"`），点击确认后调 `dispatchElevator(id)`，成功刷新并提示。
3. **管理端电梯档案详情** `ElevatorDetailView.vue`：头部操作区加同款「提前保养」按钮。

## 行为
- 生成 `PENDING` 工单，维保类别取电梯基础周期码（半月保 HM），`regionId` 照抄电梯当前区域（空 = 不限区域，全员可见），进池后由持平台 ID 的维保员到场签到抢单。
- 电梯已有未完成工单 / 无周期码 / 已停用 → 后端 `BizException(422)` 返回明确文案，前端弹错误提示，不改状态。

## 非目标
- 不让维保员在小程序自行发起提前保养（仍是管理决策）。
- 不改派单类型升级逻辑：提前保养按电梯基础周期码 HM 出单，不做 HM/TM/SM/OY 升级推断。
- 不改 mock（`POST /elevators/{id}/dispatch` 仅管理端调用，管理端直连真实后端，不经 mock）。

## 权限
`order:dispatch`：MAINT_MGR + MAINT_SUP + PLATFORM_ADMIN。
