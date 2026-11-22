-- 原记录号跨实例唯一性：重复值仅报告并阻断迁移，须人工核对后再执行。

SELECT original_record_id, COUNT(*) AS duplicate_count
FROM work_order
WHERE original_record_id IS NOT NULL
GROUP BY original_record_id
HAVING COUNT(*) > 1;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE work_order ADD UNIQUE KEY uk_work_order_original_record_id (original_record_id)',
    'SELECT 1')
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order'
  AND INDEX_NAME = 'uk_work_order_original_record_id');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT original_record_id, version, COUNT(*) AS duplicate_count
FROM maintain_record
WHERE original_record_id IS NOT NULL
GROUP BY original_record_id, version
HAVING COUNT(*) > 1;

SET @ddl := (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE maintain_record ADD UNIQUE KEY uk_maintain_record_original_version (original_record_id, version)',
    'SELECT 1')
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'maintain_record'
  AND INDEX_NAME = 'uk_maintain_record_original_version');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
