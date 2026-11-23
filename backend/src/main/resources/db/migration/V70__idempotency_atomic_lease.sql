ALTER TABLE idempotency_key
  ADD COLUMN fingerprint CHAR(64) NULL,
  ADD COLUMN owner_token VARCHAR(64) NULL,
  ADD COLUMN heartbeat_at DATETIME NULL,
  MODIFY COLUMN response_json LONGTEXT NULL;
