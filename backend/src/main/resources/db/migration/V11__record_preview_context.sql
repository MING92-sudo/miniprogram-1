-- 维保记录预览上下文快照（docs/03 §六 信息完整性清单）：地址/使用单位/维保单位/人员手机号/平台ID/签到经纬度/条目总数。
-- 随签退冻结写入，档案后续变更不回溯历史记录（TSG 第十条数据不可变，docs/05 §2.9）。
ALTER TABLE maintain_record ADD COLUMN preview_context LONGTEXT NULL COMMENT '预览上下文 JSON（MySQL 5.7 兼容用 LONGTEXT）';
