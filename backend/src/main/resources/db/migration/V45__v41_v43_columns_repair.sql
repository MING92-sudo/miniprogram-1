-- V45 修正：与 V44 同源——V41/V43 迁移若因 checksum 事故未实际落库列，读接口走投影不报错，
--      但写路径（急修提交/取消/评价/升级、门户账号合并）会 Unknown column。本迁移幂等补齐 V41/V43 列。
--      V42 的 maintain_record 列已由 V44 补齐；本迁移覆盖 use_unit(V41) 与 fault(V43)。
-- 兼容：MySQL 5.7；按 information_schema 条件化（照 V21/V44 模式），已存在跳过，重复执行不失败。

-- ── V41：use_unit.account_migrated ──
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'use_unit' AND column_name = 'account_migrated');
SET @ddl := IF(@has = 0, 'ALTER TABLE use_unit ADD COLUMN account_migrated TINYINT NOT NULL DEFAULT 0 COMMENT ''门户账号并入 sys_employee 标记（M1）''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── V43：fault 七态扩展列 ──
-- fault_reason
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'fault' AND column_name = 'fault_reason');
SET @ddl := IF(@has = 0, 'ALTER TABLE fault ADD COLUMN fault_reason VARCHAR(500) NULL COMMENT ''故障原因''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- cancel_reason
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'fault' AND column_name = 'cancel_reason');
SET @ddl := IF(@has = 0, 'ALTER TABLE fault ADD COLUMN cancel_reason VARCHAR(500) NULL COMMENT ''取消原因''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- refuse_reason
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'fault' AND column_name = 'refuse_reason');
SET @ddl := IF(@has = 0, 'ALTER TABLE fault ADD COLUMN refuse_reason VARCHAR(500) NULL COMMENT ''使用单位拒绝原因''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- evaluate_score
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'fault' AND column_name = 'evaluate_score');
SET @ddl := IF(@has = 0, 'ALTER TABLE fault ADD COLUMN evaluate_score TINYINT NULL COMMENT ''服务评价 1-5 星''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- evaluate_text
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'fault' AND column_name = 'evaluate_text');
SET @ddl := IF(@has = 0, 'ALTER TABLE fault ADD COLUMN evaluate_text VARCHAR(500) NULL COMMENT ''服务评价文字''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- evaluated_at
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'fault' AND column_name = 'evaluated_at');
SET @ddl := IF(@has = 0, 'ALTER TABLE fault ADD COLUMN evaluated_at DATETIME NULL COMMENT ''评价时间''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- escalated_at
SET @has := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'fault' AND column_name = 'escalated_at');
SET @ddl := IF(@has = 0, 'ALTER TABLE fault ADD COLUMN escalated_at DATETIME NULL COMMENT ''SLA 超时升级留痕''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;