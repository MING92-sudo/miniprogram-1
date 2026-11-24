-- 急修补「安全解救时刻 / 事发楼层」、移交整改行补「不合格描述」：三端字段契约对齐
ALTER TABLE fault
  ADD COLUMN rescued_at DATETIME NULL COMMENT '安全解救时刻（困人急修）',
  ADD COLUMN floor VARCHAR(16) NOT NULL DEFAULT '' COMMENT '事发楼层';

ALTER TABLE handover_rectification
  ADD COLUMN fail_desc VARCHAR(512) NULL COMMENT '查验不合格描述（生成整改行时自 check_item 带入）';
