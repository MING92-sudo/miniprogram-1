-- P3 客户账号独立（docs/14 §12 P3、§8 client_account 定义）：客户账号脱离 sys_employee，
-- 独立租户主体；同一单位内活跃手机号唯一（停用账号保留历史不占用），跨单位可多账号。
-- 生成列（active_phone/active_openid）在 MySQL 5.7+ 与 Flyway 均可，但为跨库兼容（测试/H2）
-- 采用应用层唯一校验 + 普通索引，docs/14 §8 "若不支持则应用层校验" 降级口径。

CREATE TABLE client_account (
  id              VARCHAR(32) NOT NULL PRIMARY KEY,
  use_unit_id     VARCHAR(32) NOT NULL COMMENT '租户：use_unit.id',
  name            VARCHAR(64) NOT NULL COMMENT '客户联系人姓名',
  phone           VARCHAR(20) NOT NULL COMMENT '登录手机号',
  password_hash   VARCHAR(100) NOT NULL COMMENT 'BCrypt 口令哈希',
  openid          VARCHAR(64) NULL COMMENT '绑定微信 openid（服务端 jscode2session 换取）',
  role_code       VARCHAR(32) NOT NULL COMMENT 'CLIENT_ADMIN/CLIENT_SIGNER',
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/GRACE/DISABLED',
  grace_until     DATETIME NULL COMMENT 'GRACE 截止时间',
  enabled         TINYINT NOT NULL DEFAULT 1 COMMENT '兼容旧字段；status 为事实源',
  session_id      VARCHAR(64) NULL COMMENT '单会话令牌 id',
  scope_version   INT NOT NULL DEFAULT 1 COMMENT '权限/状态变更后递增，旧 token 立即失效',
  created_by      VARCHAR(32) NULL COMMENT '开通人（内部账号 id）',
  created_at      DATETIME NOT NULL,
  updated_at      DATETIME NULL,
  disabled_at     DATETIME NULL,
  disabled_reason VARCHAR(255) NULL COMMENT 'MANUAL_*/GRACE_EXPIRED/MIGRATED_*',
  last_login_at   DATETIME NULL,
  KEY idx_client_phone (phone),
  KEY idx_client_unit (use_unit_id),
  KEY idx_client_openid (openid),
  KEY idx_client_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='使用单位客户账号（非员工；独立租户主体）';

-- P3 客户签字人快照（docs/14 §12 P3 验收项）：签字/拒签写入具体账号与姓名快照，
-- 先签为准的历史可追溯，签字动作不触发平台 2.6。
ALTER TABLE maintain_record
  ADD COLUMN client_account_id            VARCHAR(32) NULL COMMENT '客户签字账号 id',
  ADD COLUMN client_signer_name           VARCHAR(64) NULL COMMENT '签字人姓名快照',
  ADD COLUMN client_signed_at             DATETIME NULL COMMENT '客户签字时间',
  ADD COLUMN refused_by_client_account_id VARCHAR(32) NULL COMMENT '客户拒签账号 id',
  ADD COLUMN client_refuser_name          VARCHAR(64) NULL COMMENT '拒签人姓名快照',
  ADD COLUMN client_refuse_reason         VARCHAR(500) NULL COMMENT '客户拒签原因（独立于内部 refuseReason）',
  ADD COLUMN client_refused_at            DATETIME NULL COMMENT '客户拒签时间';
