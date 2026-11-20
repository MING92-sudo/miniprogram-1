-- P3 收口：客户主体唯一载体 = client_account（docs/14 §6 / R7）。
--
-- 背景：V41 曾把使用单位门户账号并入 sys_employee（主角色 UNIT_ADMIN / CLIENT_*），
-- V50 迁移要求 sys_user_role.org_id 非空，org_id 为空的历史行被漏迁 —— 结果「使用单位档案账号 0，
-- 但该联系人仍能用员工通道登录」，且客户账号停用与员工停用是两套开关。
--
-- 本迁移：补迁漏掉的客户主体 → 删除员工表里的客户角色行与纯客户人员行。
-- 幂等：补迁带 NOT EXISTS（同单位同手机号不重复）；删除只针对「主角色为客户」的行。
-- 注意：不使用临时表——Flyway 连接执行 SET NAMES utf8mb4 后，临时表列会落成
-- utf8mb4_general_ci，与业务表的 utf8mb4_0900_ai_ci 连接报 1267。

-- 1) 补迁（单位锚点：客户角色行 org_id → 缺失时按单位联系人/安全管理员手机号兜底匹配）
INSERT INTO client_account (
  id, use_unit_id, name, phone, password_hash, openid, role_code, status,
  grace_until, enabled, session_id, scope_version, created_by, created_at, updated_at,
  disabled_at, disabled_reason, last_login_at
)
SELECT s.emp_id, s.unit_id, s.name, s.phone, s.password_hash, s.openid, s.role_code,
       s.status, NULL, s.enabled, NULL, s.scope_version, NULL, NOW(), NOW(),
       s.disabled_at, s.disabled_reason, NULL
FROM (
  SELECT e.id AS emp_id,
         COALESCE(
           (SELECT MIN(ur.org_id) FROM sys_user_role ur
             WHERE ur.user_id = e.id
               AND ur.role_code IN ('CLIENT_ADMIN', 'CLIENT_SIGNER')
               AND ur.org_id IS NOT NULL),
           (SELECT u1.id FROM use_unit u1
             WHERE u1.unit_principal_phone = e.phone
                OR u1.elevator_administer_phone = e.phone
             ORDER BY u1.id
             LIMIT 1)
         ) AS unit_id,
         COALESCE(NULLIF(e.name, ''), e.phone)                    AS name,
         e.phone,
         e.password_hash,
         e.openid,
         CASE WHEN EXISTS (SELECT 1 FROM sys_user_role ur2
                            WHERE ur2.user_id = e.id AND ur2.role_code = 'CLIENT_SIGNER')
              THEN 'CLIENT_SIGNER' ELSE 'CLIENT_ADMIN' END        AS role_code,
         CASE WHEN e.enabled = 0 THEN 'DISABLED' ELSE 'ACTIVE' END AS status,
         COALESCE(e.enabled, 1)                                    AS enabled,
         COALESCE(e.scope_version, 1)                              AS scope_version,
         CASE WHEN e.enabled = 0 THEN NOW() ELSE NULL END           AS disabled_at,
         CASE WHEN e.enabled = 0 THEN 'MIGRATED_FROM_EMPLOYEE' ELSE NULL END AS disabled_reason
    FROM sys_employee e
   WHERE e.phone IS NOT NULL AND e.phone <> ''
     AND e.password_hash IS NOT NULL
     AND e.role IN ('CLIENT_ADMIN', 'CLIENT_SIGNER', 'UNIT_ADMIN')
) s
WHERE s.unit_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM client_account ca
                   WHERE ca.use_unit_id = s.unit_id AND ca.phone = s.phone);

-- 2) 删除客户角色行（客户角色只应存在于 client_account.role_code）
DELETE ur
  FROM sys_user_role ur
  JOIN sys_employee e ON e.id = ur.user_id
 WHERE e.role IN ('CLIENT_ADMIN', 'CLIENT_SIGNER', 'UNIT_ADMIN')
   AND ur.role_code IN ('CLIENT_ADMIN', 'CLIENT_SIGNER', 'UNIT_ADMIN');

-- 3) 删除纯客户员工行（先确认已存在对应客户账号，避免丢账号；客户签字历史走
--    maintain_record.client_account_id + 姓名快照，不依赖 sys_employee.id）
DELETE e
  FROM sys_employee e
 WHERE e.role IN ('CLIENT_ADMIN', 'CLIENT_SIGNER', 'UNIT_ADMIN')
   AND NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = e.id)
   AND EXISTS (SELECT 1 FROM client_account ca WHERE ca.id = e.id OR ca.phone = e.phone);
