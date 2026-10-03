-- 签到定位校验（docs/02 §5.4 阈值配置 / docs/04 A.0.1 码表 1001、1005）。
-- 电梯级阈值（米）为空时回退全局默认 app.checkin-threshold-meters（默认 200）；
-- geo_status：UNKNOWN=坐标未采集（签到只留痕、不拦截）/ PROVIDED=使用单位提供 / SELF_COLLECTED=现场签到回填。
ALTER TABLE elevator ADD COLUMN checkin_threshold INT NULL COMMENT '电梯级签到阈值(米)；空=回退全局默认';
ALTER TABLE elevator ADD COLUMN geo_status VARCHAR(16) NULL COMMENT '坐标来源：UNKNOWN/PROVIDED/SELF_COLLECTED';
