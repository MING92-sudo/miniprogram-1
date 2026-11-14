-- V42 记录生命周期（M1，规格 §4.1 / 蓝图 §6.2）：叠加在签退之后，2.6 上报时机不变（D2）。
-- 列随 T2 落地（菜单 badges 依赖）；生命周期逻辑与端点见 RecordLifecycleService（T4）。
ALTER TABLE maintain_record
    ADD COLUMN lifecycle_status VARCHAR(32) NULL COMMENT '记录生命周期（M1）：PENDING_REVIEW/PENDING_CLIENT_SIGN/PENDING_CLIENT_SIGNATURE/SIGNED/ARCHIVED/REFUSED_RETURNED',
    ADD COLUMN refuse_reason    VARCHAR(500) NULL COMMENT '拒签/退回原因（必填场景由应用层校验）',
    ADD COLUMN archived_at      DATETIME     NULL COMMENT '自动归档时间',
    ADD COLUMN archive_file_id  VARCHAR(32)  NULL COMMENT '归档 PDF fileId',
    ADD COLUMN archive_hash     VARCHAR(64)  NULL COMMENT '归档 PDF SHA-256';

-- 存量回填：已确认=已签署（归档 PDF 由 T4 补生成路径按需触发）；待确认=待使用单位签字
UPDATE maintain_record SET lifecycle_status = 'SIGNED'
 WHERE confirm_status = 'CONFIRMED' AND lifecycle_status IS NULL;
UPDATE maintain_record SET lifecycle_status = 'PENDING_CLIENT_SIGN'
 WHERE confirm_status = 'PENDING' AND lifecycle_status IS NULL;

-- 复核开关（默认关=免复核直达，与原型基准一致）；管理端配置页维护
INSERT INTO sys_config (id, cfg_key, cfg_value, updated_at)
VALUES ('cfg_record_review', 'record_review_enabled', '0', NOW())
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;