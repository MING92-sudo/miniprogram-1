-- M1 权限底座回填（spec §6.1）：dev/test 由存量旧角色派生 sys_user_role + sys_org 节点。
-- 生产空库时 INSERT...SELECT 命中 0 行（仅部门骨架 VALUES 3 行，无账号/无业务数据）；张工归 DemoDataSeeder。
-- 区域仍留独立 region 表，不进 org 树（spec §4.4）。旧码值不改（spec §4.6 / M1-5）。
-- 主键长度护栏：所有派生 id 用 前缀(3) + SUBSTRING(MD5(...),1,28) = 31 ≤ VARCHAR(32)；
--   sys_org.id 与 sys_user_role.org_id 锚定必须用同一表达式。INSERT IGNORE 撞唯一键即跳过（可重跑）。

-- 1) 组织节点：公司（company）/ 三部门 / 班组（group_name）/ 使用单位单级（use_unit）。
INSERT IGNORE INTO sys_org (id, type, name, code, parent_id, leader_id, status, sort_order, created_at)
SELECT CONCAT('oc_', SUBSTRING(MD5(id), 1, 28)), 'COMPANY', name, CONCAT('CO_', id), NULL, NULL, 'ACTIVE', 0, NOW()
FROM company;

INSERT IGNORE INTO sys_org (id, type, name, code, parent_id, leader_id, status, sort_order, created_at)
VALUES
  ('org_dept_install', 'DEPT', '安装部', 'DEPT_INSTALL', NULL, NULL, 'ACTIVE', 1, NOW()),
  ('org_dept_maint',   'DEPT', '维保部', 'DEPT_MAINT',   NULL, NULL, 'ACTIVE', 2, NOW()),
  ('org_dept_unit',    'DEPT', '使用单位', 'DEPT_UNIT',  NULL, NULL, 'ACTIVE', 3, NOW());

-- 班组节点：每个非空 group_name 一个 TEAM 节点，挂维保部下。
INSERT IGNORE INTO sys_org (id, type, name, code, parent_id, leader_id, status, sort_order, created_at)
SELECT CONCAT('ot_', SUBSTRING(MD5(group_name), 1, 28)), 'TEAM', group_name, CONCAT('TEAM_', MD5(group_name)),
       'org_dept_maint', NULL, 'ACTIVE', 0, NOW()
FROM sys_employee
WHERE group_name IS NOT NULL AND group_name <> ''
GROUP BY group_name;

-- 使用单位单级节点：每个 use_unit 一个 UNIT 节点，挂使用单位部下（应用层禁挂子节点）。
INSERT IGNORE INTO sys_org (id, type, name, code, parent_id, leader_id, status, sort_order, created_at)
SELECT CONCAT('ou_', SUBSTRING(MD5(id), 1, 28)), 'UNIT', unit_name, CONCAT('UNIT_', id), 'org_dept_unit', NULL, 'ACTIVE', 0, NOW()
FROM use_unit;

-- 2) 员工角色回填：旧码→规范码（M0 表1；ASSISTANT/WORKER 合并 MAINT_TECH，D-A6）。
--    org_id 锚定：TEAM 角色（MAINT_SUP/MAINT_TECH）→班组节点（与上方 ot_ 表达式一致）；其余 NULL。
INSERT IGNORE INTO sys_user_role (id, user_id, user_type, role_code, org_id, created_at)
SELECT CONCAT('ur_', SUBSTRING(MD5(CONCAT(e.id, '_', m.new_code)), 1, 28)), e.id, 'EMPLOYEE', m.new_code,
       CASE WHEN m.new_code IN ('MAINT_SUP', 'MAINT_TECH') AND e.group_name IS NOT NULL AND e.group_name <> ''
            THEN CONCAT('ot_', SUBSTRING(MD5(e.group_name), 1, 28)) ELSE NULL END,
       NOW()
FROM sys_employee e
JOIN (
  SELECT 'ADMIN' AS old_code, 'PLATFORM_ADMIN' AS new_code
  UNION ALL SELECT 'SYS_ADMIN', 'SYS_ADMIN'
  UNION ALL SELECT 'INSTALL_MANAGER', 'INST_PM'
  UNION ALL SELECT 'INSTALL_CLERK', 'INST_CLERK'
  UNION ALL SELECT 'INSTALL_WORKER', 'INSTALLER'
  UNION ALL SELECT 'MAINT_MANAGER', 'MAINT_MGR'
  UNION ALL SELECT 'LEADER', 'MAINT_SUP'
  UNION ALL SELECT 'WORKER', 'MAINT_TECH'
  UNION ALL SELECT 'ASSISTANT', 'MAINT_TECH'
  UNION ALL SELECT 'MAINT_BIZ', 'MAINT_BIZ'
  UNION ALL SELECT 'MAINT_QUALITY', 'MAINT_QUALITY'
  UNION ALL SELECT 'UNIT_ADMIN', 'CLIENT_ADMIN'
) m ON e.role = m.old_code;

-- 3) 使用单位门户账号回填（P-3）：use_unit 已开通账号 → CLIENT_ADMIN + CLIENT_SIGNER（user_type=UNIT）。
--    org_id 锚定单位节点（与上方 ou_ 表达式一致）。
INSERT IGNORE INTO sys_user_role (id, user_id, user_type, role_code, org_id, created_at)
SELECT CONCAT('ur_', SUBSTRING(MD5(CONCAT(u.id, '_CLIENT_ADMIN')), 1, 28)), u.id, 'UNIT', 'CLIENT_ADMIN',
       CONCAT('ou_', SUBSTRING(MD5(u.id), 1, 28)), NOW()
FROM use_unit u WHERE u.account IS NOT NULL AND u.account <> '';

INSERT IGNORE INTO sys_user_role (id, user_id, user_type, role_code, org_id, created_at)
SELECT CONCAT('ur_', SUBSTRING(MD5(CONCAT(u.id, '_CLIENT_SIGNER')), 1, 28)), u.id, 'UNIT', 'CLIENT_SIGNER',
       CONCAT('ou_', SUBSTRING(MD5(u.id), 1, 28)), NOW()
FROM use_unit u WHERE u.account IS NOT NULL AND u.account <> '';