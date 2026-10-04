-- 年检报告（一梯一档）：每梯保留最新一份 PDF 报告
ALTER TABLE elevator
    ADD COLUMN inspection_report_file_id VARCHAR(64) NULL AFTER next_check_date,
    ADD COLUMN inspection_report_url VARCHAR(512) NULL AFTER inspection_report_file_id,
    ADD COLUMN inspection_report_uploaded_at DATETIME NULL AFTER inspection_report_url;
