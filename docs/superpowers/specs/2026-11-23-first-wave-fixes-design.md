# 第一波四项修复设计

## 范围与顺序

1. `deleteElevator` 与派单共享电梯行锁，串行化删除和建单。
2. `HandoversView.signNode` 按 detailSeq 与目标单 ID 防止切串单。
3. 离职转交按员工 ID 字典序锁定 target/heir，消除双向死锁。
4. 幂等 query 多值排序；租约测试统一使用 `TimeUtil.now()`。

## 共同约束

- 每项独立 codex 分支，先 RED 后最小修复，聚焦测试和相关回归通过后本地提交。
- 不合 master、不推送；docs/superpowers 产物不提交。
- 不改 DispatchService 派单基线，不扩业务规则，不执行真实监管写。
- 真实并发使用随机隔离 MySQL；第四阶段独占文件不动。

## 方案选择

- 删除方使用与派单相同电梯行的 `FOR UPDATE` 锁，不重写派单逻辑。
- 签字同时校验目标 ID 和序号，迟到响应不刷新新单。
- 双方按规范顺序锁定后再读取/校验，避免相反锁顺序。
- query 仅规范化参数值顺序，不改变 token/指纹安全语义；测试时间使用项目统一时钟。
