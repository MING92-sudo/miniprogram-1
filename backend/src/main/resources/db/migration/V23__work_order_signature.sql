-- 双人维保协同：签字各自手机签（用户需求 2026-11-11，docs/01 §3.7.5）
--
-- 背景：此前签字在签退时由主维保在同一手机一次性提交「主维保 + 配合人员」两个签名，
--      配合人员无法在自己手机签字；且签退不区分角色，配合人员也能点签退。
--      现改为：签字独立成接口，主维保/配合人员各自在自己手机签字，工单存签字状态；
--      签退（仅主维保）前校验双签名齐全（docs/01 §3.7.5「主维保和配合人员分别手写签名」）。
--
-- 处理：work_order 增加主维保/配合人员各自签字 fileId 与签字时间，签退快照前冻结。
--      （只存 fileId 不带签名 URL，读路径经 FileStorageService 重签，AGENTS §2.5）

ALTER TABLE work_order
    ADD COLUMN worker_signature_file_id VARCHAR(64) NULL COMMENT '主维保签字 fileId（各自手机签）' AFTER checklist_json,
    ADD COLUMN worker_signature_at DATETIME NULL COMMENT '主维保签字时间' AFTER worker_signature_file_id,
    ADD COLUMN assistant_signature_file_id VARCHAR(64) NULL COMMENT '配合人员签字 fileId' AFTER worker_signature_at,
    ADD COLUMN assistant_signature_at DATETIME NULL COMMENT '配合人员签字时间' AFTER assistant_signature_file_id;
