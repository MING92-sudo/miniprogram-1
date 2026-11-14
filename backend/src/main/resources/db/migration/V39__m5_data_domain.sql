-- M5 数据域 schema（spec §5.1）：业务配置 + 敏感导出审批。纯 schema，全环境执行。
CREATE TABLE sys_config (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  cfg_key     VARCHAR(64)  NOT NULL,
  cfg_value   VARCHAR(255) NOT NULL,
  updated_by  VARCHAR(32)  NULL,
  updated_at  DATETIME     NOT NULL,
  UNIQUE KEY uk_config_key (cfg_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务配置（SLA/保养周期/抽查比例/脱敏开关/签字模板，M5）';

CREATE TABLE biz_export_approval (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  applicant   VARCHAR(32)  NOT NULL,
  purpose     VARCHAR(255) NOT NULL COMMENT '用途≥10字',
  scope       VARCHAR(64)  NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
  approved_by VARCHAR(32)  NULL,
  created_at  DATETIME     NOT NULL,
  approved_at DATETIME     NULL,
  KEY idx_approval_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感导出审批（M5）';