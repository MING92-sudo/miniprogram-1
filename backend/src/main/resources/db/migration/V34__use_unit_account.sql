-- 使用单位门户账号（W3，Task 9）
-- 编号说明：原计划写 V30，但 V31（权限底座）已先于本迁移执行过；V30 留空会让 Flyway
--          校验报 "Detected resolved migration not applied to database: 30" 并拒绝启动，
--          故按“只追加、版本单调”重编为 V34（V31→V33 已占用，V32 为检查清单提案预留）。
--
-- 背景：使用单位是三个平级部门之一，但账号不建在 sys_employee（不属维保单位员工目录），
--       直接挂在 use_unit 上，一间单位一个账号，角色固定 UNIT_ADMIN（docs/14 §3）。
-- 登录：账号 + 密码 → 自建 JWT，subject=use_unit.id，claims 带 ut=1 让 AuthInterceptor 走门户分支；
-- 微信绑定：管理端开通时发 8 位邀请码，安全管理员在小程序用邀请码绑定微信（openid 只由服务端 jscode2session 换取）。

ALTER TABLE use_unit
    ADD COLUMN account       VARCHAR(64)  NULL COMMENT '门户登录账号（管理端开通，全局唯一）' AFTER entity_id,
    ADD COLUMN password_hash VARCHAR(128) NULL COMMENT 'BCrypt 口令哈希，空=未开通' AFTER account,
    ADD COLUMN invite_code   VARCHAR(16)  NULL COMMENT '微信绑定邀请码（8 位，可重新生成）' AFTER password_hash,
    ADD COLUMN openid        VARCHAR(128) NULL COMMENT '绑定微信 openid（仅服务端换取，不信任请求头）' AFTER invite_code,
    ADD COLUMN bound_at      DATETIME     NULL COMMENT '微信绑定时间' AFTER openid;

-- MySQL 唯一索引允许多个 NULL（未开通账号的单位不受影响）
CREATE UNIQUE INDEX uk_use_unit_account ON use_unit (account);
CREATE UNIQUE INDEX uk_use_unit_invite ON use_unit (invite_code);
