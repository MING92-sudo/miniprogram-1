-- V67：移交归档统一使用单位档案，三类平台编码全库唯一（2026-11-22）
-- 每个 DDL 独立幂等：前序步骤已成功时重跑自动跳过；
-- 存量任一编码重复则对应唯一索引失败，人工清理后可继续重跑，不会静默合并。

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE handover_order ADD COLUMN use_unit_id VARCHAR(64) NULL COMMENT ''使用单位档案引用，归档时复制到 elevator'' AFTER project_name',
    'SELECT 1')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'handover_order' AND COLUMN_NAME = 'use_unit_id');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE elevator ADD UNIQUE KEY uk_elevator_code (elevator_code)',
    'SELECT 1')
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'elevator' AND INDEX_NAME = 'uk_elevator_code');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE elevator ADD UNIQUE KEY uk_elevator_reg_code (reg_code)',
    'SELECT 1')
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'elevator' AND INDEX_NAME = 'uk_elevator_reg_code');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE elevator ADD UNIQUE KEY uk_elevator_device_code (device_code)',
    'SELECT 1')
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'elevator' AND INDEX_NAME = 'uk_elevator_device_code');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
