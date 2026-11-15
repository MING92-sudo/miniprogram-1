-- V44 修正：V42 记录生命周期列因 checksum 事故（已执行迁移被改写 + repair 仅对齐 checksum 未重跑）
--      未实际落库部分列，导致 /admin/records 查询报 Unknown column 'work_order_id'。
--      本迁移幂等补齐 maintain_record 生命周期列与复核开关；已存在的列跳过，重复执行不失败。
-- 兼容：MySQL 5.7（云开发 MySQL 5.7，见 docs/11 步骤 A）；按 information_schema 条件化（照 V21 模式）。

-- lifecycle_status
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'maintain_record' AND column_name = 'lifecycle_status');
SET @ddl := IF(@has = 0, 'ALTER TABLE maintain_record ADD COLUMN lifecycle_status VARCHAR(32) NULL COMMENT ''记录生命周期（M1）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- work_order_id
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'maintain_record' AND column_name = 'work_order_id');
SET @ddl := IF(@has = 0, 'ALTER TABLE maintain_record ADD COLUMN work_order_id VARCHAR(32) NULL COMMENT ''来源工单 id''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- refuse_reason
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'maintain_record' AND column_name = 'refuse_reason');
SET @ddl := IF(@has = 0, 'ALTER TABLE maintain_record ADD COLUMN refuse_reason VARCHAR(500) NULL COMMENT ''拒签/退回原因''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- archived_at
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'maintain_record' AND column_name = 'archived_at');
SET @ddl := IF(@has = 0, 'ALTER TABLE maintain_record ADD COLUMN archived_at DATETIME NULL COMMENT ''自动归档时间''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- archive_file_id
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'maintain_record' AND column_name = 'archive_file_id');
SET @ddl := IF(@has = 0, 'ALTER TABLE maintain_record ADD COLUMN archive_file_id VARCHAR(32) NULL COMMENT ''归档 PDF fileId''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- archive_hash
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'maintain_record' AND column_name = 'archive_hash');
SET @ddl := IF(@has = 0, 'ALTER TABLE maintain_record ADD COLUMN archive_hash VARCHAR(64) NULL COMMENT ''归档 PDF SHA-256''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量回填（仅空值，幂等）：已确认=已签署；待确认=待使用单位签字
UPDATE maintain_record SET lifecycle_status = 'SIGNED'
 WHERE confirm_status = 'CONFIRMED' AND lifecycle_status IS NULL;
UPDATE maintain_record SET lifecycle_status = 'PENDING_CLIENT_SIGN'
 WHERE confirm_status = 'PENDING' AND lifecycle_status IS NULL;

-- 复核开关（默认关=免复核直达）；幂等
INSERT INTO sys_config (id, cfg_key, cfg_value, updated_at)
VALUES ('cfg_record_review', 'record_review_enabled', '0', NOW())
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;