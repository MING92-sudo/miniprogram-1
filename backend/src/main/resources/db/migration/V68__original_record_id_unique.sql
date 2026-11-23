-- 原记录号跨实例唯一性：重复值仅报告并阻断迁移，须人工核对后再执行。

SELECT original_record_id, COUNT(*) AS duplicate_count
FROM work_order
WHERE original_record_id IS NOT NULL
GROUP BY original_record_id
HAVING COUNT(*) > 1;

SET @ddl := (SELECT CASE
    WHEN COUNT(*) = 0 THEN
        'ALTER TABLE work_order ADD UNIQUE KEY uk_work_order_original_record_id (original_record_id)'
    WHEN MAX(NON_UNIQUE) = 0 AND MAX(SUB_PART) IS NULL
        AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'original_record_id' THEN
        'SELECT 1'
    ELSE
        'ALTER TABLE work_order ADD UNIQUE KEY uk_work_order_original_record_id (original_record_id)'
END
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order'
  AND INDEX_NAME = 'uk_work_order_original_record_id');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT original_record_id, version, COUNT(*) AS duplicate_count
FROM maintain_record
WHERE original_record_id IS NOT NULL
GROUP BY original_record_id, version
HAVING COUNT(*) > 1;

SET @ddl := (SELECT CASE
    WHEN COUNT(*) = 0 THEN
        'ALTER TABLE maintain_record ADD UNIQUE KEY uk_maintain_record_original_version (original_record_id, version)'
    WHEN MAX(NON_UNIQUE) = 0 AND MAX(SUB_PART) IS NULL
        AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'original_record_id,version' THEN
        'SELECT 1'
    ELSE
        'ALTER TABLE maintain_record ADD UNIQUE KEY uk_maintain_record_original_version (original_record_id, version)'
END
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'maintain_record'
  AND INDEX_NAME = 'uk_maintain_record_original_version');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
