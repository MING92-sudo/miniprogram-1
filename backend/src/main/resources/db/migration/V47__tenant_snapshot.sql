ALTER TABLE maintain_record
  ADD COLUMN use_unit_id   VARCHAR(32)  NULL COMMENT '生成时冻结的租户归属（P2 快照）',
  ADD COLUMN use_unit_name VARCHAR(128) NULL COMMENT '生成时冻结的单位名称（PDF/归档用）',
  ADD COLUMN elevator_id   VARCHAR(32)  NULL COMMENT '生成时冻结的电梯 id';
ALTER TABLE fault
  ADD COLUMN use_unit_id   VARCHAR(32)  NULL COMMENT '登记时冻结的租户归属',
  ADD COLUMN use_unit_name VARCHAR(128) NULL COMMENT '登记时冻结的单位名称';
ALTER TABLE work_order
  ADD COLUMN use_unit_id   VARCHAR(32)  NULL COMMENT '派单时冻结的租户归属',
  ADD COLUMN use_unit_name VARCHAR(128) NULL COMMENT '派单时冻结的单位名称';

-- 快照谓词索引随列同批（P2b 客户查询即依赖；不能推迟到 V49——P2 先于 P3 独立上线，§19-22）
CREATE INDEX idx_record_unit ON maintain_record (use_unit_id);
CREATE INDEX idx_fault_unit  ON fault (use_unit_id);
CREATE INDEX idx_order_unit  ON work_order (use_unit_id);

-- 回填/迁移人工核对清单表：P2a 回填 Runner 即写入，随 V47 建（原排 V49，§19-22 修订）
CREATE TABLE client_migration_issue (
  id                VARCHAR(32) PRIMARY KEY,
  issue_type        VARCHAR(24) NOT NULL COMMENT 'DEDUP_CONFLICT/NO_ANCHOR/CROSS_PRINCIPAL/MIXED_SKIPPED/TRANSFER_SUSPECT/CODE_AMBIGUOUS/UNMATCHED',
  object_type       VARCHAR(16) NOT NULL COMMENT 'ACCOUNT/RECORD/FAULT/ORDER',
  object_id         VARCHAR(64) NOT NULL,
  elevator_code     VARCHAR(64) NULL,
  current_unit_id   VARCHAR(32) NULL,
  candidate_unit_id VARCHAR(32) NULL,
  evidence          VARCHAR(500) NULL COMMENT '证据摘要（工单 id/合同 id/change id/冲突双方）',
  confidence        VARCHAR(8)  NULL COMMENT 'HIGH/MID/LOW',
  resolution        VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/BACKFILLED/SKIPPED/MANUAL_DONE',
  decision_json     TEXT NULL COMMENT '人工裁定（DEDUP_CONFLICT 取源/NO_ANCHOR 补锚点），Runner 重跑消费（§19-15）',
  resolve_note      VARCHAR(255) NULL,
  resolved_by       VARCHAR(32) NULL,
  resolved_at       DATETIME NULL,
  created_at        DATETIME NOT NULL,
  UNIQUE KEY uk_issue (object_type, object_id, issue_type),
  KEY idx_issue_pending (resolution, issue_type)
) COMMENT='P2 回填 / P3 账号迁移的人工核对清单（幂等 upsert）';
