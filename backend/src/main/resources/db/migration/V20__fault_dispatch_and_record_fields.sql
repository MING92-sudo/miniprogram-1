-- 急修单派单 + 维保记录补采集字段（用户需求 2026-10-05，docs/04 V2.28）
-- ① fault 增加派单字段：接单维保员 / 派单时间（状态 OPEN 待派单 → ASSIGNED 已派单 → CLOSED 已闭环）
-- ② maintain_record 增加安全防护确认（JSON 勾选）与待办事项
ALTER TABLE fault
    ADD COLUMN dispatch_worker_id VARCHAR(32) NULL AFTER status,
    ADD COLUMN dispatched_at DATETIME NULL AFTER dispatch_worker_id;

ALTER TABLE maintain_record
    ADD COLUMN safety_json VARCHAR(300) NULL AFTER photos_json,
    ADD COLUMN todo_desc VARCHAR(500) NULL AFTER safety_json;
