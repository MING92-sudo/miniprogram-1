-- V61：管理员角色统一（2026-11-21 用户裁决）——唯一全权角色=平台管理员（PLATFORM_ADMIN，旧码 ADMIN），
-- SYS_ADMIN（系统管理员）并入：存量账号角色改写（代码侧 SYS_ADMIN 已是 PLATFORM_ADMIN 别名，
-- 本迁移只做数据归一；开发期数据可重建）。
-- sys_user_role（user_id,user_type,role_code 唯一）：先去重再改写，避免唯一键冲突。
DELETE ur1 FROM sys_user_role ur1
  JOIN sys_user_role ur2 ON ur1.user_id = ur2.user_id AND ur1.user_type = ur2.user_type
   AND ur2.role_code = 'PLATFORM_ADMIN'
  WHERE ur1.role_code = 'SYS_ADMIN';
UPDATE sys_user_role SET role_code = 'PLATFORM_ADMIN' WHERE role_code = 'SYS_ADMIN';
UPDATE sys_employee SET role = 'ADMIN' WHERE role = 'SYS_ADMIN';
