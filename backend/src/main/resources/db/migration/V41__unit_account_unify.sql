-- V41 门户账号并入员工体系（M1，规格 §3.1）
-- 数据合并由 UnitAccountUnifyService 启动后幂等执行（同部门合并/跨部门保留兜底/密码优先/openid 不抢夺）；
-- 本迁移只加标记列，不 DELETE 任何数据（留存红线）。旧门户列保留至 M6/生产清库。
ALTER TABLE use_unit
    ADD COLUMN account_migrated TINYINT NOT NULL DEFAULT 0 COMMENT '门户账号并入 sys_employee 标记（M1）：0=未迁移兜底，1=已迁移';