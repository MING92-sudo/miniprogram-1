-- 急修单编号（用户需求）：BWJX + 时间(yyyyMMddHHmm) + 当日顺序3位 = 19 位；
-- 新增维修过程字段：到场时间（以签到为准）/现场情况描述/维修结束时间/待办事项
-- 生产库 MySQL 5.7 无 IF NOT EXISTS；2026-10-04 首次执行时 ALTER 已生效但回填失败，
-- 配合启动时 flyway.repair()，本迁移改为幂等：列已存在则跳过对应 DDL。
SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE fault ADD COLUMN fault_no VARCHAR(19) NULL AFTER id', 'SELECT 1')
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fault' AND COLUMN_NAME = 'fault_no');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE fault ADD COLUMN arrived_at DATETIME NULL AFTER created_at', 'SELECT 1')
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fault' AND COLUMN_NAME = 'arrived_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE fault ADD COLUMN site_desc VARCHAR(1000) NULL AFTER descr', 'SELECT 1')
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fault' AND COLUMN_NAME = 'site_desc');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE fault ADD COLUMN finished_at DATETIME NULL AFTER handle_desc', 'SELECT 1')
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fault' AND COLUMN_NAME = 'finished_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE fault ADD COLUMN todo_desc VARCHAR(500) NULL AFTER finished_at', 'SELECT 1')
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'fault' AND COLUMN_NAME = 'todo_desc');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量单据按原登记时间回填编号（每分钟内按 id 顺序编号）
-- 生产库为 MySQL 5.7，无窗口函数；改用派生表 + 相关子查询按分钟内 id 顺序编号
UPDATE fault f
JOIN (
    SELECT a.id,
           (SELECT COUNT(*) FROM fault b
            WHERE DATE_FORMAT(b.created_at, '%Y%m%d%H%i') = DATE_FORMAT(a.created_at, '%Y%m%d%H%i')
              AND b.id <= a.id) AS rn
    FROM fault a
) t ON t.id = f.id
SET f.fault_no = CONCAT('BWJX', DATE_FORMAT(f.created_at, '%Y%m%d%H%i'), LPAD(t.rn, 3, '0'))
WHERE f.fault_no IS NULL;
