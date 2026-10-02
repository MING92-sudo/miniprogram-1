-- 班组数据权限（用户需求）：组长查看本组员工单据，组员仅本人
ALTER TABLE sys_employee
  ADD COLUMN group_name VARCHAR(64) NULL COMMENT '所属班组；组长按班组查看组员工单/急修单';
ALTER TABLE fault
  ADD COLUMN created_by VARCHAR(32) NULL COMMENT '急修单登记人 empId；历史数据 NULL 对全员可见';
