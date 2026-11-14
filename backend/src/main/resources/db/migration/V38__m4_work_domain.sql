-- M4 作业域加列（spec §5.2，不删不改旧列）：记录版本链+单人豁免、故障代报修+SLA、工单SLA。
ALTER TABLE maintain_record
  ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT '记录版本，修订生成 v2',
  ADD COLUMN prev_version_id VARCHAR(32) NULL COMMENT '上一版本记录 id',
  ADD COLUMN amend_reason VARCHAR(255) NULL,
  ADD COLUMN amended_by VARCHAR(32) NULL,
  ADD COLUMN amended_at DATETIME NULL,
  ADD COLUMN solo_exempt TINYINT NOT NULL DEFAULT 0 COMMENT '存量单人豁免（P-4），新工单恒0';

ALTER TABLE fault
  ADD COLUMN proxy_source VARCHAR(16) NULL COMMENT 'PHONE/WECHAT/ONSITE',
  ADD COLUMN recording_no VARCHAR(16) NULL COMMENT 'REC-\\d{4,6}',
  ADD COLUMN proxy_by VARCHAR(32) NULL,
  ADD COLUMN sla_minutes INT NULL,
  ADD COLUMN deadline DATETIME NULL;

ALTER TABLE work_order
  ADD COLUMN sla_minutes INT NULL,
  ADD COLUMN deadline DATETIME NULL;

-- 存量无配合人记录置单人豁免（P-4，仅 dev/test 过渡；生产空库 no-op）。新工单 solo_exempt 恒0。
UPDATE maintain_record SET solo_exempt = 1
  WHERE assistant_platform_id IS NULL OR assistant_platform_id = '';