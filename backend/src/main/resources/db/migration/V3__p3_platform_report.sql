-- P3 平台写链路沉淀：2.6 上报日志（脱敏摘要落库，docs/07-full-test 实测口径）
-- AGENTS §2.3：originalRecordId 幂等性未书面确认前不自动重试，失败转人工（手动重报）

CREATE TABLE reg_upload_log (
  id                 VARCHAR(32)  NOT NULL PRIMARY KEY,
  original_record_id VARCHAR(32)  NOT NULL,
  action             VARCHAR(16)  NOT NULL COMMENT 'UPLOAD/REUPLOAD/LEGACY',
  request_digest     VARCHAR(1024) NULL COMMENT '脱敏请求摘要（手机号打码，不含 token）',
  platform_code      VARCHAR(16)  NULL,
  platform_message   VARCHAR(256) NULL,
  status             VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED',
  created_at         DATETIME     NOT NULL,
  KEY idx_orig (original_record_id),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
