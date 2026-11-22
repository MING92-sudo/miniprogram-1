-- V65：移除外接建档第三条业务路径（2026-11-22 用户确认，当前无真实业务数据）
-- 保留两条建档路径：外接梯在电梯台账直建并排期；自装梯经安装移交归档并排期。
-- 不修改 V25/V29/V33/V40/V59；本迁移清理其外接专属对象及开发期派生数据。
-- MySQL DDL 隐式提交，且启动会清理失败历史后重跑：所有依赖 source_filing_id 的步骤均按列存在性守卫。

SET @source_filing_exists := (SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order' AND COLUMN_NAME = 'source_filing_id');

-- 将旧外接归档电梯归一为台账直建口径。next_maintenance_date 先计算，
-- 确保读取的是原 last_maintenance_at / handover_at；随后清空安装移交专用 handover_at。
-- 本 UPDATE 可重复执行：首次归一后 archive_source=0，重跑不再改动。
UPDATE elevator
SET next_maintenance_date = DATE_ADD(
        DATE(COALESCE(last_maintenance_at, handover_at, NOW())),
        INTERVAL COALESCE(NULLIF(interval_days, 0), 15) DAY),
    last_maintenance_at = COALESCE(last_maintenance_at, handover_at, NOW()),
    handover_at = NULL,
    archive_source = 0
WHERE archive_source = 2;

-- 保留全部工单及 maintain_record 历史，不删除已完成或已关联记录单的数据。
-- 仅把未完成旧首保从 +3 天改到刚归一的直建排期日，避免日历继续采用旧锚点。
-- 若上次失败已删掉 source_filing_id，则本步跳过；列仍在时可安全重跑。
SET @ddl := IF(@source_filing_exists > 0,
    'UPDATE work_order w JOIN elevator e ON e.id = w.elevator_id
     SET w.plan_time = TIMESTAMP(e.next_maintenance_date, ''09:00:00'')
     WHERE w.source_filing_id IS NOT NULL AND w.status <> ''DONE''
       AND e.next_maintenance_date IS NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

DROP TABLE IF EXISTS external_takeover_check_item;
DROP TABLE IF EXISTS external_takeover_check;
DROP TABLE IF EXISTS external_filing_doc;
DROP TABLE IF EXISTS external_filing_order;
DROP TABLE IF EXISTS handover_confirmation;

SET @ddl := IF(@source_filing_exists > 0,
    'ALTER TABLE work_order DROP COLUMN source_filing_id',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

ALTER TABLE elevator
    MODIFY COLUMN archive_source TINYINT NOT NULL DEFAULT 0
    COMMENT '建档来源：0=台账直建/1=安装移交';
