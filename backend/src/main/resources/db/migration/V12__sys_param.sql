-- 系统参数表（docs/02 §5.4：签到定位阈值三级配置中"品种级 + 全局级"的载体）。
-- 解析顺序：电梯级 elevator.checkin_threshold
--        → 品种级 sys_param['checkin.threshold.category.<品种名>']
--        → 全局   sys_param['checkin.threshold.default']
--        → 配置   app.checkin-threshold-meters（APP_CHECKIN_THRESHOLD_METERS，默认 200）。
-- 品种级阈值属业务/合规参数（地下室机房与地面机房定位漂移差异大），未确认前不预置数值：
-- 缺行即回退全局默认，由运维按需 INSERT，例如：
--   INSERT INTO sys_param (param_key, param_value, remark) VALUES ('checkin.threshold.category.液压驱动电梯','300','液压梯机房漂移大');
CREATE TABLE sys_param (
  param_key   VARCHAR(96)  NOT NULL PRIMARY KEY,
  param_value VARCHAR(255) NOT NULL,
  remark      VARCHAR(255) NULL,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_param (param_key, param_value, remark) VALUES
 ('checkin.threshold.default', '200', '签到定位阈值全局默认（米）；电梯级/品种级优先，docs/02 §5.4');
