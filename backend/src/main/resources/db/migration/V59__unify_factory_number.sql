-- V59：产品编号彻底统一为出厂编号 factoryNumber（2026-11-21 用户口径，开发期数据可重建）
-- elevator：删 product_no（保留 factory_number）；两张单据表：列改名 product_no → factory_number
ALTER TABLE elevator DROP COLUMN product_no;
ALTER TABLE handover_order CHANGE COLUMN product_no factory_number VARCHAR(64) NULL COMMENT '出厂编号（原 product_no）';
ALTER TABLE external_filing_order CHANGE COLUMN product_no factory_number VARCHAR(64) NULL COMMENT '出厂编号（原 product_no）';
