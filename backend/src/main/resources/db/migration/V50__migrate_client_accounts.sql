-- P3 存量客户账号迁移（docs/14 §12 P3）：把 sys_employee 中以 CLIENT_*/UNIT_ADMIN 角色
-- 存在的过渡账号，迁移到独立的 client_account 表。
--
-- 幂等性：NOT EXISTS 保证重复执行不产生重复行；新库（sys_employee 为空）执行结果为 0 行。
-- 去重口径：一个员工只迁一条（子查询按 user_id 聚合，取最小 org_id 作单位锚点）；
--           同一使用单位 + 同一手机号亦不重复迁（NOT EXISTS）。
-- 旧行策略：**不从 sys_employee 删除**——历史签字/审计仍引用 employee id，删除会破坏追溯；
-- 登录入口已切换为 /auth/client/login（只认 client_account），旧行仅为留档。
-- 角色判定：V35 之后以 sys_user_role.role_code 为准，兼容旧 sys_employee.role='UNIT_ADMIN'。

INSERT INTO client_account (
  id, use_unit_id, name, phone, password_hash, openid, role_code, status,
  grace_until, enabled, session_id, scope_version, created_by, created_at, updated_at,
  disabled_at, disabled_reason, last_login_at
)
SELECT
  e.id,
  u.unit_id,
  COALESCE(NULLIF(e.name, ''), e.phone)                                 AS name,
  e.phone,
  e.password_hash,
  e.openid,
  CASE WHEN u.has_signer = 1 THEN 'CLIENT_SIGNER' ELSE 'CLIENT_ADMIN' END AS role_code,
  CASE WHEN e.enabled = 1 THEN 'ACTIVE' ELSE 'DISABLED' END             AS status,
  NULL                                                                  AS grace_until,
  COALESCE(e.enabled, 1)                                                AS enabled,
  NULL                                                                  AS session_id,  -- 旧会话一律作废，迁移后需重新登录
  COALESCE(e.scope_version, 1)                                          AS scope_version,
  NULL                                                                  AS created_by,
  NOW()                                                                 AS created_at,  -- sys_employee 无 created_at 列，以迁移时间登记
  NOW()                                                                 AS updated_at,
  CASE WHEN e.enabled = 1 THEN NULL ELSE NOW() END                      AS disabled_at,
  CASE WHEN e.enabled = 1 THEN NULL ELSE 'MIGRATED_FROM_EMPLOYEE' END   AS disabled_reason,
  NULL                                                                  AS last_login_at
FROM sys_employee e
JOIN (
  SELECT user_id,
         MIN(org_id)                                                    AS unit_id,
         MAX(CASE WHEN role_code = 'CLIENT_SIGNER' THEN 1 ELSE 0 END)   AS has_signer,
         MAX(CASE WHEN role_code IN ('CLIENT_ADMIN', 'CLIENT_SIGNER')
                  THEN 1 ELSE 0 END)                                    AS is_client_role
    FROM sys_user_role
   WHERE org_id IS NOT NULL
   GROUP BY user_id
) u ON u.user_id = e.id
WHERE (u.is_client_role = 1
       OR e.role IN ('CLIENT_ADMIN', 'CLIENT_SIGNER', 'UNIT_ADMIN'))
  AND e.phone IS NOT NULL AND e.phone <> ''
  AND NOT EXISTS (
        SELECT 1 FROM client_account ca
         WHERE ca.use_unit_id = u.unit_id AND ca.phone = e.phone
      );
