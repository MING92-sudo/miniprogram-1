-- C-12 安装移交维保检查清单（GB/T 10060—2023 表1，74 项）落地。只追加，不改历史迁移。
-- 旧 6 类 item_type 保留为兼容列；新单写 G01—G13 项类 + item_no 1—74。

ALTER TABLE handover_order
  ADD COLUMN install_complete_date VARCHAR(10) NULL,
  ADD COLUMN install_unit_name VARCHAR(128) NULL,
  ADD COLUMN special_type VARCHAR(64) NULL;

ALTER TABLE handover_check
  ADD COLUMN total_items INT NULL,
  ADD COLUMN pass_count INT NULL,
  ADD COLUMN fail_count INT NULL,
  ADD COLUMN na_count INT NULL,
  ADD COLUMN important_fail_count INT NULL,
  ADD COLUMN conclusion VARCHAR(24) NULL,
  ADD COLUMN snapshot_json TEXT NULL;

ALTER TABLE handover_check_item
  ADD COLUMN item_no INT NULL,
  ADD COLUMN item_group VARCHAR(8) NULL,
  ADD COLUMN clause_no VARCHAR(16) NULL,
  ADD COLUMN item_name VARCHAR(128) NULL,
  ADD COLUMN requirement VARCHAR(512) NULL,
  ADD COLUMN important TINYINT NULL,
  ADD COLUMN measured_value VARCHAR(64) NULL,
  ADD COLUMN fail_desc VARCHAR(512) NULL,
  ADD COLUMN na_reason VARCHAR(16) NULL,
  ADD COLUMN na_note VARCHAR(255) NULL,
  ADD COLUMN photo_file_ids TEXT NULL;

ALTER TABLE handover_rectification
  ADD COLUMN item_no INT NULL,
  ADD COLUMN resp_party VARCHAR(16) NULL,
  ADD COLUMN due_date VARCHAR(10) NULL,
  ADD COLUMN rect_measure VARCHAR(512) NULL,
  ADD COLUMN rectified_at DATETIME NULL,
  ADD COLUMN rectifier_name VARCHAR(64) NULL,
  ADD COLUMN source VARCHAR(16) NULL,
  ADD COLUMN voided TINYINT NOT NULL DEFAULT 0;

ALTER TABLE handover_confirmation
  ADD COLUMN handover_id VARCHAR(32) NULL;

CREATE TABLE handover_doc (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  handover_id VARCHAR(32)  NOT NULL,
  doc_no      INT          NOT NULL COMMENT 'D1—D20',
  doc_name    VARCHAR(255) NOT NULL,
  required    TINYINT      NOT NULL DEFAULT 0,
  file_id     VARCHAR(32)  NULL,
  na_reason   VARCHAR(255) NULL,
  uploaded_at DATETIME     NULL,
  KEY idx_hdoc_handover (handover_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='移交资料核对 D1—D20（C-12）';

CREATE TABLE handover_photo (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  handover_id VARCHAR(32)  NOT NULL,
  pose_code   VARCHAR(32)  NOT NULL COMMENT '6 定角',
  file_id     VARCHAR(32)  NOT NULL,
  taken_at    DATETIME     NULL,
  gps         VARCHAR(64)  NULL,
  KEY idx_hphoto_handover (handover_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='移交定角照片（C-12）';

CREATE TABLE handover_archive (
  id          VARCHAR(32)  NOT NULL PRIMARY KEY,
  handover_id VARCHAR(32)  NOT NULL,
  file_id     VARCHAR(32)  NULL,
  category    VARCHAR(32)  NOT NULL,
  seq         INT          NOT NULL,
  sha256      VARCHAR(64)  NULL,
  archived_at DATETIME     NOT NULL,
  KEY idx_harchive_handover (handover_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='移交归档目录+SHA-256（C-12，4 年留存）';