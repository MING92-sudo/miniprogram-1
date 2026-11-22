-- V66：2.6 人员身份唯一性（2026-11-22）
-- MySQL 唯一索引允许多个 NULL：未登记人员统一存 NULL，非空平台人员 ID 全库唯一。
UPDATE sys_employee
SET platform_id = NULL
WHERE platform_id IS NOT NULL AND TRIM(platform_id) = '';

-- 不自动删除或合并重复员工：若存量非空 ID 重复，本语句故意失败，
-- 必须先人工确认真实人员映射并清理重复值，再重跑迁移，禁止静默取首条。
ALTER TABLE sys_employee
    ADD UNIQUE KEY uk_employee_platform_id (platform_id);
