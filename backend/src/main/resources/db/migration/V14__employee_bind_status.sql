-- 维保人员平台绑定状态（2.4 changState：0 建立/正常，1 中止）；
-- 绑定正常的人员在档案处禁止删除，须先在人员管理中止。
ALTER TABLE sys_employee
    ADD COLUMN bind_status TINYINT NOT NULL DEFAULT 0 AFTER sync_status;
