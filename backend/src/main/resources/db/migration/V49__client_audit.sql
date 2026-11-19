-- P3 客户租户审计列（docs/14 §12 P3、§7）：sys_audit_log 增加 use_unit_id，
-- 客户审计写租户 id、内部审计留空；不复用 org_id，避免内部组织与客户租户混淆。
ALTER TABLE sys_audit_log
  ADD COLUMN use_unit_id VARCHAR(32) NULL COMMENT '客户租户 id；内部审计为空，不复用 org_id';
