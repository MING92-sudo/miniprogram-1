-- 急修单改造（docs/04 A.6）：故障位置照片 / 使用单位安全管理员签字 / 确认时间
ALTER TABLE fault
  ADD COLUMN photos       VARCHAR(1024) NULL COMMENT '故障位置照片 url 列表，JSON 数组',
  ADD COLUMN signature    VARCHAR(512)  NULL COMMENT '使用单位安全管理员签字图 url',
  ADD COLUMN confirmed_at DATETIME      NULL COMMENT '使用单位确认时间';
