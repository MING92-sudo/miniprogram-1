-- 单端登录：同账号签发新 token 即覆盖 session_id，旧 token 请求直接 401
ALTER TABLE sys_employee
    ADD COLUMN session_id VARCHAR(64) NULL AFTER sync_status;
