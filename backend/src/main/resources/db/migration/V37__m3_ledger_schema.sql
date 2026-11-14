-- M3 台账域 schema（spec §5.1 域表）：邀请码/组织二维码 + 项目台账 + 电梯变更双人确认。
-- 建表约定跟随仓库（VARCHAR(32) 字符串主键 + VARCHAR(16/32) 枚举 + DATETIME + KEY idx_ + utf8mb4 + COMMENT）。
-- 纯 schema，全环境执行；不含业务数据删除（生产清库为人工上线步骤，AGENTS §6）。

CREATE TABLE sys_invite (
  id           VARCHAR(32)  NOT NULL PRIMARY KEY,
  code         VARCHAR(64)  NOT NULL COMMENT '邀请码值/组织二维码令牌',
  type         VARCHAR(16)  NOT NULL COMMENT 'INVITE/ORG_QR',
  target_org_id VARCHAR(32) NULL COMMENT '目标组织 sys_org.id',
  created_by   VARCHAR(32)  NOT NULL,
  valid_days   INT          NOT NULL DEFAULT 7 COMMENT '有效期 1—30 天',
  expires_at   DATETIME     NOT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/USED/EXPIRED/WITHDRAWN',
  joined_count INT          NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL,
  UNIQUE KEY uk_invite_code (code),
  KEY idx_invite_org (target_org_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邀请码/组织二维码（M3）';

CREATE TABLE biz_project (
  id            VARCHAR(32)  NOT NULL PRIMARY KEY,
  project_no    VARCHAR(32)  NOT NULL COMMENT '项目编号 YQ-2025-08',
  name          VARCHAR(128) NOT NULL,
  use_unit_id   VARCHAR(32)  NULL,
  maint_org_id  VARCHAR(32)  NULL COMMENT '维保项目部 sys_org.id',
  install_org_id VARCHAR(32) NULL COMMENT '安装单位 sys_org.id',
  elevator_count INT         NOT NULL DEFAULT 0,
  contract_id   VARCHAR(32)  NULL,
  contract_end_date VARCHAR(10) NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/TAKEOVER/EXPIRED/PAUSED',
  last_work_date VARCHAR(10) NULL,
  created_at    DATETIME     NOT NULL,
  UNIQUE KEY uk_project_no (project_no),
  KEY idx_project_unit (use_unit_id),
  KEY idx_project_maint (maint_org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目台账（M3）';

CREATE TABLE biz_elevator_change (
  id           VARCHAR(32)  NOT NULL PRIMARY KEY,
  elevator_id  VARCHAR(32)  NOT NULL,
  change_type  VARCHAR(16)  NOT NULL COMMENT 'TRANSFER/DISABLE/SCRAP',
  initiator_id VARCHAR(32)  NOT NULL,
  confirmer_id VARCHAR(32)  NULL COMMENT '确认人，必须不同于发起人',
  reason       VARCHAR(255) NOT NULL,
  before_snap  TEXT         NULL,
  after_snap   TEXT         NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CONFIRMED/REJECTED',
  created_at   DATETIME     NOT NULL,
  confirmed_at DATETIME     NULL,
  KEY idx_change_elevator (elevator_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电梯移装/停用/报废双人确认（M3）';