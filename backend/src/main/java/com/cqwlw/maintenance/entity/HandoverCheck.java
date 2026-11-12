package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 移交现场查验记录（Task 4）：一次 submitInspect 落一条 check + 若干 check_item */
@TableName("handover_check")
@Data
public class HandoverCheck {
    @TableId
    public String id;
    public String handoverId;
    public String checkerId;
    public String checkerName;
    public LocalDateTime checkedAt;
    public String remark;
}
