INSERT INTO sys_role_scope (role_code, scope, scope_ref, version)
SELECT 'LEADER', 'TEAM', NULL, 1
WHERE NOT EXISTS (SELECT 1 FROM sys_role_scope WHERE role_code = 'LEADER');

UPDATE sys_user_role ur
JOIN sys_employee e ON e.id = ur.user_id AND ur.user_type = 'EMPLOYEE'
SET ur.role_code = 'LEADER'
WHERE e.role = 'LEADER' AND ur.role_code = 'MAINT_SUP';

UPDATE sys_employee
SET scope_version = scope_version + 1
WHERE role = 'LEADER';
