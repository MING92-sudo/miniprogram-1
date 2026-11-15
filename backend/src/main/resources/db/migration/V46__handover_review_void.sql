-- V46 C-13：移交内部审核（PENDING_INST_REVIEW）与作废（VOIDED）留痕列。
-- 资料齐全提交进入待安装部审核；安装主管审核通过→INSPECTING、退回→DOC_SUBMITTING（原因+期限）；
-- 任意未生效态可作废（原因必填）。审核/作废记录操作人/时间。
-- 兼容 MySQL 5.7；按 information_schema 条件化（照 V44/V45 模式），已存在跳过，重复执行不失败。

-- review_by_id
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'handover_order' AND column_name = 'review_by_id');
SET @ddl := IF(@has = 0, 'ALTER TABLE handover_order ADD COLUMN review_by_id VARCHAR(32) NULL COMMENT ''内部审核人 ID（C-13）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- review_by_name
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'handover_order' AND column_name = 'review_by_name');
SET @ddl := IF(@has = 0, 'ALTER TABLE handover_order ADD COLUMN review_by_name VARCHAR(64) NULL COMMENT ''内部审核人姓名（C-13）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- reviewed_at
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'handover_order' AND column_name = 'reviewed_at');
SET @ddl := IF(@has = 0, 'ALTER TABLE handover_order ADD COLUMN reviewed_at DATETIME NULL COMMENT ''内部审核时间（C-13）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- review_remark
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'handover_order' AND column_name = 'review_remark');
SET @ddl := IF(@has = 0, 'ALTER TABLE handover_order ADD COLUMN review_remark VARCHAR(512) NULL COMMENT ''审核退回原因（C-13）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- review_deadline
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'handover_order' AND column_name = 'review_deadline');
SET @ddl := IF(@has = 0, 'ALTER TABLE handover_order ADD COLUMN review_deadline VARCHAR(16) NULL COMMENT ''审核退回整改期限 yyyy-MM-dd（C-13）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- void_reason
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'handover_order' AND column_name = 'void_reason');
SET @ddl := IF(@has = 0, 'ALTER TABLE handover_order ADD COLUMN void_reason VARCHAR(512) NULL COMMENT ''作废原因（C-13）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- void_by_id
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'handover_order' AND column_name = 'void_by_id');
SET @ddl := IF(@has = 0, 'ALTER TABLE handover_order ADD COLUMN void_by_id VARCHAR(32) NULL COMMENT ''作废操作人 ID（C-13）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- void_by_name
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'handover_order' AND column_name = 'void_by_name');
SET @ddl := IF(@has = 0, 'ALTER TABLE handover_order ADD COLUMN void_by_name VARCHAR(64) NULL COMMENT ''作废操作人姓名（C-13）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- voided_at
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'handover_order' AND column_name = 'voided_at');
SET @ddl := IF(@has = 0, 'ALTER TABLE handover_order ADD COLUMN voided_at DATETIME NULL COMMENT ''作废时间（C-13）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;