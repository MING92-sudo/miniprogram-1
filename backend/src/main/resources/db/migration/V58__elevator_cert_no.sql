-- V58：电梯「登记证编码」独立字段（2026-11-21 用户口径）
-- 使用登记证上的编号，形如 梯11渝BSP8194(20)；独立于 reg_code（注册代码，20 位），
-- 仅本系统档案使用，不上报监管平台 2.6（20 字段报文不含此项）。
ALTER TABLE elevator ADD COLUMN cert_no VARCHAR(64) NULL COMMENT '使用登记证编码（独立于注册代码，不上报平台）' AFTER reg_code;
