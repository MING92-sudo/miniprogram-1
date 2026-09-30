-- Backend MVP schema. Later migrations must be append-only.
CREATE TABLE company (
  id BIGINT NOT NULL AUTO_INCREMENT,
  organization_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  work_manager_name VARCHAR(64) NOT NULL,
  work_manager_phone VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_company_org_code (organization_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE employee (
  id BIGINT NOT NULL AUTO_INCREMENT,
  phone VARCHAR(20) NOT NULL,
  password_hash VARCHAR(120) NOT NULL,
  name VARCHAR(64) NOT NULL,
  role VARCHAR(32) NOT NULL,
  role_text VARCHAR(64) NOT NULL,
  platform_id VARCHAR(64) NULL,
  certificate VARCHAR(80) NULL,
  active TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_employee_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE use_unit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  unit_name VARCHAR(128) NOT NULL,
  unit_principal VARCHAR(64) NOT NULL,
  unit_principal_phone VARCHAR(20) NOT NULL,
  elevator_administer VARCHAR(64) NOT NULL,
  elevator_administer_phone VARCHAR(20) NOT NULL,
  emergency_phone VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE elevator (
  id BIGINT NOT NULL AUTO_INCREMENT,
  elevator_code VARCHAR(64) NOT NULL,
  elevator_name VARCHAR(128) NOT NULL,
  device_code VARCHAR(64) NOT NULL,
  registration_code VARCHAR(64) NOT NULL,
  inside_number VARCHAR(32) NULL,
  use_unit_id BIGINT NOT NULL,
  category VARCHAR(64) NOT NULL,
  longitude DECIMAL(11,7) NULL,
  latitude DECIMAL(11,7) NULL,
  next_check_date DATE NULL,
  brand VARCHAR(64) NULL,
  manufacturer VARCHAR(128) NULL,
  product_no VARCHAR(80) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_elevator_code (elevator_code),
  KEY idx_elevator_use_unit (use_unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(40) NOT NULL,
  elevator_id BIGINT NOT NULL,
  work_type VARCHAR(32) NOT NULL,
  work_type_code VARCHAR(4) NOT NULL,
  plan_time DATETIME NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  worker_name VARCHAR(64) NOT NULL,
  assistant_name VARCHAR(64) NULL,
  worker_phone VARCHAR(20) NOT NULL,
  assistant_phone VARCHAR(20) NULL,
  worker_platform_id VARCHAR(64) NULL,
  assistant_platform_id VARCHAR(64) NULL,
  checkin_time DATETIME NULL,
  checkout_time DATETIME NULL,
  duration VARCHAR(12) NULL,
  original_record_id VARCHAR(64) NULL,
  report_status VARCHAR(32) NULL,
  checklist_json MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_work_order_no (order_no),
  KEY idx_work_order_elevator_status (elevator_id, status),
  KEY idx_work_order_plan_time (plan_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE maintain_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  work_order_id BIGINT NOT NULL,
  original_record_id VARCHAR(64) NOT NULL,
  elevator_code VARCHAR(64) NOT NULL,
  device_code VARCHAR(64) NOT NULL,
  work_type VARCHAR(4) NOT NULL,
  start_time DATETIME NOT NULL,
  end_time DATETIME NOT NULL,
  report_status VARCHAR(32) NOT NULL,
  platform_message VARCHAR(255) NULL,
  snapshot_json MEDIUMTEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_maintain_record_original_id (original_record_id),
  KEY idx_maintain_record_order (work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE file_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  original_name VARCHAR(255) NOT NULL,
  storage_path VARCHAR(500) NOT NULL,
  url VARCHAR(500) NOT NULL,
  content_type VARCHAR(128) NULL,
  size BIGINT NOT NULL,
  owner_phone VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reg_upload_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  original_record_id VARCHAR(64) NOT NULL,
  api VARCHAR(64) NOT NULL,
  request_body MEDIUMTEXT NOT NULL,
  response_body MEDIUMTEXT NULL,
  success TINYINT NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  http_status INT NULL,
  platform_code VARCHAR(20) NULL,
  cost_ms BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_reg_upload_log_record (original_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE biz_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NULL,
  content_json MEDIUMTEXT NOT NULL,
  created_by VARCHAR(20) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_biz_record_type (type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE message_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(128) NOT NULL,
  content VARCHAR(1000) NOT NULL,
  type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  is_read TINYINT NOT NULL DEFAULT 0,
  receiver_phone VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_message_receiver_read (receiver_phone, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
