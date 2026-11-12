-- 区域主数据 + 电梯/员工区域字段（Task 1：移交/外接建档/使用单位门户的可见性范围谓词）
--
-- 背景：后续功能按「区域」划分移交、外接建档与使用单位门户的数据可见范围；
--       本迁移只建主数据表 region 并给 elevator/sys_employee 加可空 region_id，
--       分配接口在 RegionService（PUT /elevators/{id}/region、PUT /employees/{id}/region）。

CREATE TABLE region (
  id         VARCHAR(32)  NOT NULL PRIMARY KEY,
  name       VARCHAR(128) NOT NULL,
  sort_order INT          NULL,
  remark     VARCHAR(255) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE elevator
    ADD COLUMN region_id VARCHAR(32) NULL COMMENT '所属区域（region.id，可空；可见性范围谓词）' AFTER project_name;

ALTER TABLE sys_employee
    ADD COLUMN region_id VARCHAR(32) NULL COMMENT '所属区域（region.id，可空；可见性范围谓词）' AFTER group_name;
