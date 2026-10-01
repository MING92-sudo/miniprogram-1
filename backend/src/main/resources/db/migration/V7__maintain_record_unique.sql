-- V7: maintain_record.original_record_id 唯一约束
--
-- 背景：V1 曾建 UNIQUE KEY uk_maintain_record_original_id，V2（p2_rebuild）重建该表时
--       丢失，只剩普通索引 idx_record_id。于是「维保记录编号唯一」仅靠 Ids.nextRecordId()
--       的 6 位随机后缀保证（同一毫秒内约百万分之一量级碰撞概率），碰撞会静默写入重复编号，
--       而该编号是 2.6 上报的 originalRecordId，重复即导致上报错记录。
--       work_order.order_no 已有 uk_order_no，此处与之对齐。
--
-- 重复数据不自动清理：维保记录属合规数据，宁可让迁移失败暴露出来，也不静默删记录。
-- 若本迁移因 duplicate entry 失败，请人工核查 maintain_record 后再重跑。

SET @has_uk := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'maintain_record'
     AND index_name = 'uk_maintain_record_original_id');

SET @ddl := IF(@has_uk = 0,
  'ALTER TABLE maintain_record ADD UNIQUE KEY uk_maintain_record_original_id (original_record_id)',
  'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
