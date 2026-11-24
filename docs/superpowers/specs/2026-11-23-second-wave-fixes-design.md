# 第二波五项修复设计

## 顺序与方案

1. 离线队列：未登录拒绝入队并用页面现有 `wx.showToast` 提示，不新增 constants；每次上传 await 后、业务 request 前复核 owner，A/B 混合任务保持隔离。
2. 多角色 UI：SettingsView/RolesView/ProjectsView/PageMeta 使用 `roleSet` 与 `stateForRoles` 并集，不再只按 activeRole。
3. 工单弹窗：`resetActions` 清空 dispatch/close/cancel/proxy 的值，同时恢复 proxySource=PHONE 默认值。
4. 时间工具：无参数/undefined 仍表示当前时间；`parseTime('')=0` 保留兼容；显式 null/空串/0/非法日历日期不伪造成当前或有效时间。
5. 移交上传：后端三个文件强制，无容缺提交契约；移除“标记缺件”入口及 missing 状态，文案改为三份强制资料上传齐全才可提交。

## 共同约束

- 每项独立 codex 分支，先 RED 后最小修复，独立审查后本地提交，不合 master。
- 不改 constants/index.js 或第四阶段独占文件；不顺手重构。
- 不执行真实监管写；文档不提交。
