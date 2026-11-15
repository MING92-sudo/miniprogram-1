-- V43 急修七态扩展（M1，规格 §4.3 / 蓝图 §6.3）：状态值复用 status 列（OPEN/ASSIGNED/PROCESSING/PENDING_CONFIRM/CLOSED/REFUSED/CANCELLED）
ALTER TABLE fault
    ADD COLUMN fault_reason   VARCHAR(500) NULL COMMENT '故障原因（提交急修单必填）',
    ADD COLUMN cancel_reason  VARCHAR(500) NULL COMMENT '取消原因（必填）',
    ADD COLUMN refuse_reason  VARCHAR(500) NULL COMMENT '使用单位拒绝原因（必填）',
    ADD COLUMN evaluate_score TINYINT      NULL COMMENT '服务评价 1-5 星',
    ADD COLUMN evaluate_text  VARCHAR(500) NULL COMMENT '服务评价文字',
    ADD COLUMN evaluated_at   DATETIME     NULL COMMENT '评价时间（重复评价拦截）',
    ADD COLUMN escalated_at   DATETIME     NULL COMMENT 'SLA 超时升级留痕（系统）';