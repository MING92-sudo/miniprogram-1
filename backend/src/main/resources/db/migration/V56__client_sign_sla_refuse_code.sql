-- V56：使用单位签字端细化（2026-11-20，docs/04 V2.80）
--   ① 签字 SLA：sign_requested_at=发起签字时刻（SLA 起算，NULL=旧记录回退 created_at）、
--      sign_reminded_at=最近一次临期/超期提醒推送时刻（提醒去重，每记录至多临期+超期各一条）；
--   ② 拒签原因分类码 refuse_code（R1 记录内容与现场不符 / R2 维保项目漏项或未按单执行 /
--      R3 现场照片不清晰或与实际不符 / R4 时间或人员信息有误 / R5 其他）；
--   ③ 客户消息受众：message.use_unit_id（NULL=员工消息中心，保持旧行为；非空=该单位客户门户可见）。

ALTER TABLE maintain_record
  ADD COLUMN sign_requested_at DATETIME NULL COMMENT '发起使用单位签字时刻（签字 SLA 起算；NULL=旧记录回退 created_at）' AFTER client_refused_at,
  ADD COLUMN sign_reminded_at DATETIME NULL COMMENT '最近一次签字临期/超期提醒推送时刻（提醒去重）' AFTER sign_requested_at,
  ADD COLUMN refuse_code VARCHAR(8) NULL COMMENT '拒签原因分类码 R1—R5' AFTER refuse_reason;

ALTER TABLE message
  ADD COLUMN use_unit_id VARCHAR(64) NULL COMMENT '受众使用单位（NULL=员工消息中心；非空=该单位客户门户可见）' AFTER read_flag;

-- 签字 SLA（小时，默认 72）：管理端配置页可维护，服务侧缺省/非法值回退 72
INSERT INTO sys_config (id, cfg_key, cfg_value, updated_at)
VALUES ('cfg_client_sign_sla_hours', 'client_sign_sla_hours', '72', NOW())
ON DUPLICATE KEY UPDATE cfg_key = cfg_key;
