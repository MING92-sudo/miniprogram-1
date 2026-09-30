-- P2 最小 schema：字段名与前端契约（docs/04 A.0.1）camelCase 一一对应（下划线列 + MP 驼峰映射）
-- 复杂嵌套结构（检查项清单/记录明细/报文快照）以 JSON 列存储，服务层负责组装

CREATE TABLE company (
  id               VARCHAR(32)  NOT NULL PRIMARY KEY,
  organization_code VARCHAR(64) NOT NULL,
  name             VARCHAR(128) NOT NULL,
  entity_id        VARCHAR(64)  NULL,
  org_id           VARCHAR(64)  NULL,
  work_meneger_name VARCHAR(64) NULL,
  work_meneger_phone VARCHAR(32) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sys_employee (
  id            VARCHAR(32)  NOT NULL PRIMARY KEY,
  name          VARCHAR(64)  NOT NULL,
  phone         VARCHAR(32)  NOT NULL,
  account       VARCHAR(32)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role          VARCHAR(32)  NOT NULL,
  role_text     VARCHAR(64)  NOT NULL,
  openid        VARCHAR(128) NULL,
  platform_id   VARCHAR(64)  NULL,
  certificate   VARCHAR(64)  NULL,
  work_start_date VARCHAR(10) NULL,
  work_end_date   VARCHAR(10) NULL,
  work_stat     VARCHAR(16)  NULL,
  sync_status   VARCHAR(16)  NULL,
  UNIQUE KEY uk_account (account),
  KEY idx_openid (openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE use_unit (
  id            VARCHAR(32)  NOT NULL PRIMARY KEY,
  unit_name     VARCHAR(128) NOT NULL,
  unit_principal VARCHAR(64) NULL,
  unit_principal_phone VARCHAR(32) NULL,
  elevator_administer VARCHAR(64) NULL,
  elevator_administer_phone VARCHAR(32) NULL,
  emergency_phone VARCHAR(32) NULL,
  entity_id     VARCHAR(64)  NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE elevator (
  id            VARCHAR(32)  NOT NULL PRIMARY KEY,
  elevator_code VARCHAR(64)  NOT NULL,
  elevator_name VARCHAR(128) NOT NULL,
  location      VARCHAR(256) NULL,
  reg_code      VARCHAR(64)  NULL,
  device_code   VARCHAR(64)  NULL,
  inside_number VARCHAR(32)  NULL,
  model         VARCHAR(64)  NULL,
  use_unit_id   VARCHAR(32)  NULL,
  category      VARCHAR(32)  NULL,
  next_check_date DATE        NULL,
  next_maintenance_date DATE  NULL,
  factory_number VARCHAR(64) NULL,
  use_unit_entity_id VARCHAR(64) NULL,
  elevator_administer VARCHAR(64) NULL,
  elevator_administer_phone VARCHAR(32) NULL,
  emergency_phone VARCHAR(32) NULL,
  platform_synced_at DATETIME NULL,
  lng           DECIMAL(10,6) NULL,
  lat           DECIMAL(10,6) NULL,
  brand         VARCHAR(64)  NULL,
  manufacturer  VARCHAR(128) NULL,
  product_no    VARCHAR(64)  NULL,
  drive_mode    VARCHAR(32)  NULL,
  rated_load    INT          NULL,
  rated_load_unit VARCHAR(8) NULL,
  rated_speed   DECIMAL(6,2) NULL,
  rated_speed_unit VARCHAR(8) NULL,
  stations_doors VARCHAR(16) NULL,
  work_type_code VARCHAR(8)  NULL,
  interval_days INT          NULL,
  worker_name   VARCHAR(64)  NULL,
  worker_phone  VARCHAR(32)  NULL,
  worker_platform_id VARCHAR(64) NULL,
  assistant_name VARCHAR(64) NULL,
  assistant_platform_id VARCHAR(64) NULL,
  last_maintenance_at DATETIME NULL,
  KEY idx_code (elevator_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_order (
  id            VARCHAR(32)  NOT NULL PRIMARY KEY,
  order_no      VARCHAR(32)  NOT NULL,
  elevator_id   VARCHAR(32)  NOT NULL,
  work_type     VARCHAR(32)  NOT NULL,
  work_type_code VARCHAR(8)  NOT NULL,
  plan_time     DATETIME     NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  worker_name   VARCHAR(64)  NULL,
  assistant_name VARCHAR(64) NULL,
  worker_platform_id VARCHAR(64) NULL,
  assistant_platform_id VARCHAR(64) NULL,
  checkin_time  DATETIME     NULL,
  checkout_time DATETIME     NULL,
  duration      VARCHAR(12)  NULL,
  original_record_id VARCHAR(32) NULL,
  report_status VARCHAR(16)  NULL,
  auto_dispatched TINYINT(1) NOT NULL DEFAULT 0,
  checkin_extra JSON         NULL,
  checklist_json JSON        NULL,
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_elevator_status (elevator_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE maintain_record (
  id            VARCHAR(32)  NOT NULL PRIMARY KEY,
  elevator_name VARCHAR(128) NULL,
  elevator_code VARCHAR(64)  NULL,
  work_type     VARCHAR(32)  NULL,
  work_type_code VARCHAR(8)  NULL,
  worker_name   VARCHAR(64)  NULL,
  assistant_name VARCHAR(64) NULL,
  worker_platform_id VARCHAR(64) NULL,
  assistant_platform_id VARCHAR(64) NULL,
  checkin_time  DATETIME     NULL,
  checkout_time DATETIME     NULL,
  duration      VARCHAR(12)  NULL,
  items_json    JSON         NULL,
  photos_json   JSON         NULL,
  worker_signature_url VARCHAR(256) NULL,
  assistant_signature_url VARCHAR(256) NULL,
  problem_codes_json JSON     NULL,
  original_record_id VARCHAR(32) NULL,
  report_status VARCHAR(16)  NULL,
  retry_count   INT          NOT NULL DEFAULT 0,
  next_maintenance_date DATE  NULL,
  confirm_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  satisfaction  INT          NULL,
  signature_file_id VARCHAR(64) NULL,
  signature_url VARCHAR(256) NULL,
  share_token   VARCHAR(64)  NULL,
  report_payload_json JSON    NULL,
  created_at    DATETIME     NOT NULL,
  KEY idx_confirm (confirm_status),
  KEY idx_record_id (original_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE message (
  id         VARCHAR(32) NOT NULL PRIMARY KEY,
  title      VARCHAR(128) NOT NULL,
  content    VARCHAR(512) NOT NULL,
  created_at DATETIME    NOT NULL,
  read_flag  TINYINT(1)  NOT NULL DEFAULT 0,
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rescue (
  id            VARCHAR(32) NOT NULL PRIMARY KEY,
  elevator_code VARCHAR(64) NOT NULL,
  trapped_count INT         NOT NULL DEFAULT 0,
  descr         VARCHAR(512) NULL,
  alarm_at      DATETIME    NULL,
  depart_at     DATETIME    NULL,
  arrive_at     DATETIME    NULL,
  rescued_at    DATETIME    NULL,
  arrive_minutes INT         NULL,
  rescued_minutes INT       NULL,
  overtime      TINYINT(1)  NOT NULL DEFAULT 0,
  reason        VARCHAR(256) NULL,
  action        VARCHAR(256) NULL,
  status        VARCHAR(16) NOT NULL,
  created_at    DATETIME    NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fault (
  id            VARCHAR(32) NOT NULL PRIMARY KEY,
  elevator_code VARCHAR(64) NOT NULL,
  fault_type    VARCHAR(32) NULL,
  descr         VARCHAR(512) NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  handle_desc   VARCHAR(512) NULL,
  created_at    DATETIME    NOT NULL,
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE drill (
  id            VARCHAR(32) NOT NULL PRIMARY KEY,
  drill_date    DATE        NOT NULL,
  category      VARCHAR(32) NOT NULL,
  scene         VARCHAR(64) NULL,
  participants  VARCHAR(256) NULL,
  process       VARCHAR(512) NULL,
  problems      VARCHAR(512) NULL,
  actions       VARCHAR(512) NULL,
  created_at    DATETIME    NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inspect_record (
  id            VARCHAR(32) NOT NULL PRIMARY KEY,
  elevator_id   VARCHAR(32) NOT NULL,
  inspect_date  DATE        NOT NULL,
  item_total    INT         NOT NULL DEFAULT 0,
  abnormal_count INT        NOT NULL DEFAULT 0,
  problems      VARCHAR(1024) NULL,
  inspector_sign VARCHAR(64) NULL,
  reviewer_sign VARCHAR(64) NULL,
  KEY idx_elevator (elevator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge (
  id      VARCHAR(32)  NOT NULL PRIMARY KEY,
  title   VARCHAR(128) NOT NULL,
  tag     VARCHAR(32)  NULL,
  content TEXT         NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE app_file (
  id          VARCHAR(64)  NOT NULL PRIMARY KEY,
  object_key  VARCHAR(256) NOT NULL,
  url         VARCHAR(512) NULL,
  content_type VARCHAR(128) NULL,
  size_bytes  BIGINT       NULL,
  created_at  DATETIME     NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idempotency_key (
  idem_key    VARCHAR(64)  NOT NULL PRIMARY KEY,
  path        VARCHAR(128) NULL,
  response_json TEXT       NULL,
  created_at  DATETIME     NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
