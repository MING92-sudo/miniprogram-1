package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 外接接管查验单（Task 7）：不合格项不阻塞建档，责任归属落在 external_takeover_check_item */
@TableName("external_takeover_check")
@Data
public class ExternalTakeoverCheck {
    @TableId
    public String id;
    public String filingId;
    public String checkerId;
    public String checkerName;
    public LocalDateTime checkedAt;
    public String remark;
}
