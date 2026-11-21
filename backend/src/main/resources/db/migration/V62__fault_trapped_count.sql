-- V62 急修单困人人数（C-18 落地）：fault 表增列 trapped_count，
-- 报修表单填写 → faultView 下发 → 工作台「急修待接」KPI / 困人横幅 / 列表 urgent 过滤共用取数口径
ALTER TABLE fault
    ADD COLUMN trapped_count INT NULL COMMENT '困人人数（NULL/0=非困人急修）';
