-- 管理端二期其余（docs/09 §1.2）：预警规则/发送记录/审计日志/定位申诉审核/账号启停
-- alert_rule 9 类预警（docs/01 §3.11）：年检到期 60/合同到期 90/人员证件到期 60/维保超期(逾期-1)/
-- 配件库存/使用单位确认超时(24h=1天)/困人救援超时(电话短信)/自行检查未完成 30/应急演练超期 30

CREATE TABLE alert_rule (
  id           VARCHAR(32) NOT NULL PRIMARY KEY,
  type         VARCHAR(32) NOT NULL,
  name         VARCHAR(64) NOT NULL,
  advance_days INT         NOT NULL COMMENT '提前天数；负值=逾期后提醒',
  target       VARCHAR(128) NOT NULL,
  enabled      TINYINT(1)  NOT NULL DEFAULT 1,
  updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_alert_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO alert_rule (id, type, name, advance_days, target, enabled) VALUES
 ('al_1','YEAR_CHECK','年检到期',60,'管理端、维保人员',1),
 ('al_2','CONTRACT_EXPIRE','合同到期',90,'管理端',1),
 ('al_3','CERT_EXPIRE','人员证件到期',60,'本人、管理员',1),
 ('al_4','MAINT_OVERDUE','维保超期',-1,'分级提醒',1),
 ('al_5','PART_INVENTORY','配件库存',0,'管理员',1),
 ('al_6','CONFIRM_TIMEOUT','使用单位确认超时',1,'安全管理员、管理端',1),
 ('al_7','RESCUE_TIMEOUT','困人救援超时',0,'公司负责人、管理员（电话+短信）',1),
 ('al_8','SELF_INSPECT','自行检查未完成',30,'管理员、维保人员',1),
 ('al_9','DRILL_EXPIRE','应急演练超期',30,'公司负责人、管理员',1);

CREATE TABLE alert_record (
  id         VARCHAR(32) NOT NULL PRIMARY KEY,
  rule_type  VARCHAR(32) NOT NULL,
  title      VARCHAR(128) NOT NULL,
  content    VARCHAR(512) NOT NULL,
  target     VARCHAR(128) NULL,
  status     VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED',
  read_flag  TINYINT(1)  NOT NULL DEFAULT 0,
  created_at DATETIME    NOT NULL,
  KEY idx_alert_type (rule_type),
  KEY idx_alert_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notify_record (
  id                 VARCHAR(32) NOT NULL PRIMARY KEY,
  type               VARCHAR(32) NOT NULL,
  template_id        VARCHAR(64) NULL,
  target_employee_id VARCHAR(32) NULL,
  target_role        VARCHAR(32) NULL,
  title              VARCHAR(128) NOT NULL,
  content            VARCHAR(512) NOT NULL,
  channel            VARCHAR(16) NOT NULL COMMENT 'SUBSCRIBE/INBOX',
  status             VARCHAR(16) NOT NULL COMMENT 'SENT/FAILED/PENDING',
  created_at         DATETIME    NOT NULL,
  KEY idx_notify_type (type, status),
  KEY idx_notify_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE op_log (
  id            VARCHAR(32) NOT NULL PRIMARY KEY,
  operator_id   VARCHAR(32) NULL,
  operator_name VARCHAR(64) NULL,
  method        VARCHAR(8)  NOT NULL,
  path          VARCHAR(128) NOT NULL,
  action        VARCHAR(64) NULL,
  result        VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILED',
  ip            VARCHAR(64) NULL,
  created_at    DATETIME    NOT NULL,
  KEY idx_op_path (path),
  KEY idx_op_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE location_appeal (
  id             VARCHAR(32) NOT NULL PRIMARY KEY,
  work_order_id  VARCHAR(32) NOT NULL,
  employee_id    VARCHAR(32) NULL,
  reason         VARCHAR(255) NOT NULL,
  photo_key      VARCHAR(255) NULL,
  distance       DECIMAL(10,2) NULL,
  threshold      DECIMAL(10,2) NULL,
  status         VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  reviewer_id    VARCHAR(32) NULL,
  review_comment VARCHAR(255) NULL,
  reviewed_at    DATETIME    NULL,
  created_at     DATETIME    NOT NULL,
  KEY idx_appeal_order (work_order_id),
  KEY idx_appeal_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE sys_employee ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '账号启用（SYS_ADMIN 管理，停用后无法登录）';
UPDATE sys_employee SET enabled = 1;
