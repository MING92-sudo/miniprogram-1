-- 双端会话隔离：管理端（web）与小程序（mp）各占一端，同端互踢、跨端互不干扰
ALTER TABLE sys_employee
    ADD COLUMN session_admin VARCHAR(64) NULL AFTER session_id;
