-- 移交单/外接建档单创建时间（Task 14 管理端列表按时间倒序）
--
-- 背景：V27/V29 建表时只留了状态与业务时间（确认时间/查验时间），没有创建时间，
--       管理端列表无法按"最近提交"排序。补一列并回填（回填值是近似值：确认时间或当前时间），
--       新单据在 init() 里写入精确时间。
-- 编号跳过 V32：docs/15 安装移交检查清单的落地提案预留了 V32。

ALTER TABLE handover_order
    ADD COLUMN created_at DATETIME NULL COMMENT '发起时间（列表排序用）' AFTER handover_no;
UPDATE handover_order SET created_at = COALESCE(confirmed_at, NOW()) WHERE created_at IS NULL;

ALTER TABLE external_filing_order
    ADD COLUMN created_at DATETIME NULL COMMENT '发起时间（列表排序用）' AFTER filing_no;
UPDATE external_filing_order SET created_at = COALESCE(confirmed_at, NOW()) WHERE created_at IS NULL;
