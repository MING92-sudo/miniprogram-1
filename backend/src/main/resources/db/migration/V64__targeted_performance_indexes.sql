-- V64 定向性能索引：版本链、列表排序、签署队列、调度、急修 SLA 与日志。
-- 仅做索引维护，不处理业务数据；上线前需备份、维护窗口与 EXPLAIN 验证。

SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_mr_prev_version ON maintain_record(prev_version_id)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='maintain_record' AND index_name='idx_mr_prev_version');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_mr_order_version ON maintain_record(work_order_id,version,created_at)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='maintain_record' AND index_name='idx_mr_order_version');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_mr_created_at ON maintain_record(created_at)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='maintain_record' AND index_name='idx_mr_created_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_mr_sign_queue ON maintain_record(confirm_status,lifecycle_status,created_at)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='maintain_record' AND index_name='idx_mr_sign_queue');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)>0, 'DROP INDEX idx_confirm ON maintain_record', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='maintain_record' AND index_name='idx_confirm');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_wo_plan_time ON work_order(plan_time)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='work_order' AND index_name='idx_wo_plan_time');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_wo_elevator_status_checkout ON work_order(elevator_id,status,checkout_time)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='work_order' AND index_name='idx_wo_elevator_status_checkout');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)>0, 'DROP INDEX idx_elevator_status ON work_order', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='work_order' AND index_name='idx_elevator_status');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_fault_created_at ON fault(created_at)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='fault' AND index_name='idx_fault_created_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_fault_unit_created ON fault(use_unit_id,created_at)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='fault' AND index_name='idx_fault_unit_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_fault_escalation ON fault(escalated_at,deadline)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='fault' AND index_name='idx_fault_escalation');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)>0, 'DROP INDEX idx_fault_unit ON fault', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='fault' AND index_name='idx_fault_unit');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_reg_orig_created ON reg_upload_log(original_record_id,created_at)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='reg_upload_log' AND index_name='idx_reg_orig_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl := (SELECT IF(COUNT(*)>0, 'DROP INDEX idx_orig ON reg_upload_log', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='reg_upload_log' AND index_name='idx_orig');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*)=0, 'CREATE INDEX idx_elevator_unit ON elevator(use_unit_id)', 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='elevator' AND index_name='idx_elevator_unit');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
