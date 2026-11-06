-- 电梯档案新增「项目名称（小区名称）」字段（用户需求 2026-11-06）
--
-- 背景：设备档案「本地维护信息」此前把「项目名称」错填为使用单位名称（use_unit.unit_name，物业公司名）。
--      项目名称应为电梯所在的小区/楼盘名称（如「世纪大厦」「蓝湾国际」），与使用单位名称是两个概念，
--      且需由维保人员现场核对后可直接更正（管理端可能写错）。
--
-- 处理：elevator 增加独立字段 project_name，承载项目名称（小区名称）；与 use_unit.unit_name 解耦。

ALTER TABLE elevator
    ADD COLUMN project_name VARCHAR(128) NULL AFTER elevator_name COMMENT '项目名称（小区名称，维保人员现场可维护）';
