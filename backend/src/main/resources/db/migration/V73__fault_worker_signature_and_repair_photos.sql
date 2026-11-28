-- 急修单补维保人员签字与维修过程照片：小程序现场签署/拍照，PDF 第四、五区块展示
ALTER TABLE fault
  ADD COLUMN worker_signature VARCHAR(512)  NULL COMMENT '维保人员签字图 url',
  ADD COLUMN repair_photos    VARCHAR(2048) NULL COMMENT '维修过程照片 url 列表，JSON 数组';
