-- 双人动态码时效化 + 主维保/配合人员分别签到（docs/01 §3.7.2「主维保人员和配合人员分别签到」、docs/03 §3.2 项6）。
-- 动态码由 HMAC(app.jwt-secret, 工单ID|5 秒步长) 派生：每 5 秒刷新、60 秒有效、绑定工单、一次性使用。
ALTER TABLE work_order ADD COLUMN dynamic_code VARCHAR(8) NULL COMMENT '已消费（用过）的双人动态码，用于防重放；NULL/空=未使用';
ALTER TABLE work_order ADD COLUMN dynamic_code_expires_at DATETIME NULL COMMENT '该已消费码的失效时刻（GMT+8）';
-- 配合人员签到留痕独立存储：原实现只有 checkin_extra 一条，第二人签到会覆盖第一人的定位/照片/时间。
ALTER TABLE work_order ADD COLUMN assistant_checkin_extra LONGTEXT NULL COMMENT '配合人员签到留痕 JSON（MySQL 5.7 兼容用 LONGTEXT）';
