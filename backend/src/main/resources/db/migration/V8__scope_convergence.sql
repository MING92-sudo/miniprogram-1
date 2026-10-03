-- 范围收敛（docs/09 V3.3 §6.6）：电梯停用状态。
-- NULL/ACTIVE = 在保；INACTIVE = 停用（逻辑删除：不派单、小程序不可见，记录保留满足 TSG 第十条 ≥4 年）。
ALTER TABLE elevator ADD COLUMN status VARCHAR(16) NULL;
