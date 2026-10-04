-- 急修单编号（用户需求）：BWJX + 时间(yyyyMMddHHmm) + 当日顺序3位 = 19 位；
-- 新增维修过程字段：到场时间（以签到为准）/现场情况描述/维修结束时间/待办事项
ALTER TABLE fault
    ADD COLUMN fault_no VARCHAR(19) NULL AFTER id,
    ADD COLUMN arrived_at DATETIME NULL AFTER created_at,
    ADD COLUMN site_desc VARCHAR(1000) NULL AFTER descr,
    ADD COLUMN finished_at DATETIME NULL AFTER handle_desc,
    ADD COLUMN todo_desc VARCHAR(500) NULL AFTER finished_at;

-- 存量单据按原登记时间回填编号（每分钟内按 id 顺序编号）
UPDATE fault
SET fault_no = CONCAT('BWJX', DATE_FORMAT(created_at, '%Y%m%d%H%i'),
                      LPAD(ROW_NUMBER() OVER (PARTITION BY DATE_FORMAT(created_at, '%Y%m%d%H%i') ORDER BY id), 3, '0'))
WHERE fault_no IS NULL;
