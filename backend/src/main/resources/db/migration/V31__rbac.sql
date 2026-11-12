-- 账号权限底座 P1（docs/14 §9.1）：sys_employee 增部门与直属上级
--
-- 背景：角色只有码值、没有"部门"概念，安装部/维保部/使用单位分不开；也没有上级链，
--       无法表达"上级可见下级"。本迁移只加三列并按现有角色回填部门，
--       判定逻辑在 auth/RoleCatalog、auth/PermissionMatrix、service/DeptScopeService。
--       新建档未填 department 时按 role 回退（RoleCatalog.departmentOf），不影响存量账号。

ALTER TABLE sys_employee
    ADD COLUMN department VARCHAR(16) NULL COMMENT '部门：INSTALL/MAINT/UNIT/PLATFORM（docs/14 §4 部门域互斥）' AFTER role_text,
    ADD COLUMN manager_id VARCHAR(64) NULL COMMENT '直属上级 empId（上级可见下级，docs/14 §5）' AFTER department,
    ADD COLUMN job_title  VARCHAR(32) NULL COMMENT '岗位名称（展示用）' AFTER manager_id;

-- 存量回填（docs/14 §9.5 迁移路径）
UPDATE sys_employee SET department = 'MAINT'    WHERE role IN ('LEADER', 'WORKER', 'ASSISTANT');
UPDATE sys_employee SET department = 'PLATFORM' WHERE role IN ('ADMIN', 'SYS_ADMIN');
UPDATE sys_employee SET department = 'UNIT'     WHERE role = 'UNIT_ADMIN';
