-- 移交单遗留问题标记（Task 5 后半：首保签退时 handoverIssue=true 回写来源移交单）
-- 背景：Task 4 建的 handover_order 没有遗留问题标记列；首保（HM）签退发现移交遗留问题时
--       需要回写来源移交单并通知安装部，故补此列（默认 false）。

ALTER TABLE handover_order
    ADD COLUMN handover_issue BOOLEAN NOT NULL DEFAULT FALSE COMMENT '首保签退回写的遗留问题标记' AFTER elevator_id;
