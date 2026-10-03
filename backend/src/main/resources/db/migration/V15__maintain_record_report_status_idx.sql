-- 上报异常清单按 report_status 高频过滤（docs/04 A.0.1），补索引
ALTER TABLE maintain_record
    ADD INDEX idx_report_status (report_status);
