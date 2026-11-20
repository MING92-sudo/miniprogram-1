-- V57：工单区域池快照（2026-11-20 区域抢单池设计，D2/M4）
--   work_order.region_id：派单时冻结电梯 regionId，区域抢单池按此列检索候选工单；
--   仅存快照，电梯后续调整区域不回写历史工单。

ALTER TABLE work_order
  ADD COLUMN region_id VARCHAR(64) NULL COMMENT '区域池快照（派单时取电梯 regionId，2026-11-20 区域抢单池设计）';

CREATE INDEX idx_wo_region ON work_order(region_id);
