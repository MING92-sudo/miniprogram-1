-- P0 修复（docs/04 A.1）：openid 串号数据清理 + 唯一约束
--
-- 背景：修复前 /auth/bind-wechat 只读 X-WX-OPENID，缺失时回退 app.dev-openid 并写库，
--      多个账号会落库同一 openid；/auth/wx-login 按 openid LIMIT 1 查账号 → 互相登入对方账号。
--      代码侧已改为 code → jscode2session 换真实 openid（WxAuthService），且不再信任
--      X-WX-OPENID / dev_openid；本迁移负责清理已被污染的存量数据并加库层约束。
--
-- 处理：
--   ① 清掉已知兜底值（历史 application.yml: app.dev-openid 默认 dev_openid）；
--   ② 仍被多个账号共用的 openid 一律置空，强制重新绑定（不猜归属，避免把工单给错人）；
--   ③ 加唯一约束，从库层保证「一个微信只对应一个账号」（openid 为 NULL 的未绑定行不受影响，
--      MySQL 唯一索引允许多行 NULL）。
--
-- 健壮性：全部语句可重复执行（DBA 手工改过索引 / 重跑清理时不会失败）。Flyway 版本化迁移
--      失败会阻塞后续所有启动，而 MySQL 5.7 无 DROP INDEX IF EXISTS，故索引操作按
--      information_schema 条件化（PREPARE，5.7/8.x 均支持）。
--
-- 兼容：MySQL 5.7（云端为云开发 MySQL 5.7，见 docs/11 步骤 A）。

UPDATE sys_employee SET openid = NULL WHERE openid = 'dev_openid';

UPDATE sys_employee e
  JOIN (
    SELECT openid FROM sys_employee
     WHERE openid IS NOT NULL
     GROUP BY openid HAVING COUNT(*) > 1
  ) dup ON dup.openid = e.openid
   SET e.openid = NULL;

SET @has_old := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'sys_employee' AND index_name = 'idx_openid');
SET @ddl := IF(@has_old > 0, 'ALTER TABLE sys_employee DROP INDEX idx_openid', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_new := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'sys_employee' AND index_name = 'uk_openid');
SET @ddl := IF(@has_new = 0, 'ALTER TABLE sys_employee ADD UNIQUE KEY uk_openid (openid)', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
