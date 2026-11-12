-- 电梯建档来源 + 工单来源/遗留问题字段（Task 2：移交/外接建档/使用单位门户）
ALTER TABLE elevator
    ADD COLUMN archive_source TINYINT NOT NULL DEFAULT 0 COMMENT '建档来源：0=存量直建/1=移交/2=外接' AFTER region_id,
    ADD COLUMN supervision_pass_date DATE NULL COMMENT '监督检验合格日（移交首保基线锚点）' AFTER archive_source,
    ADD COLUMN handover_at DATETIME NULL COMMENT '归档时间=维保责任起始时间' AFTER supervision_pass_date;

ALTER TABLE work_order
    ADD COLUMN source_handover_id VARCHAR(32) NULL COMMENT '首保来源移交单（可空）' AFTER assistant_signature_at,
    ADD COLUMN source_filing_id VARCHAR(32) NULL COMMENT '首保来源外接建档单（可空）' AFTER source_handover_id,
    ADD COLUMN handover_issue TINYINT(1) NOT NULL DEFAULT 0 COMMENT '移交遗留问题勾选（默认 false）' AFTER source_filing_id;
