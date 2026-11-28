-- 急修单补报修联系人：客户报修自动带登录账号姓名/电话，管理端代报修按录入值落库
ALTER TABLE fault
  ADD COLUMN reporter_name  VARCHAR(64) NULL COMMENT '报修人姓名',
  ADD COLUMN reporter_phone VARCHAR(32) NULL COMMENT '报修人联系电话';
