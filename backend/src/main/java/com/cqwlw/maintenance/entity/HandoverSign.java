package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 移交节点确认记录（Task 6 signNode 消费，本任务仅建表）：内部账号「节点通过即签字」 */
@TableName("handover_sign")
@Data
public class HandoverSign {
    @TableId
    public String id;
    public String handoverId;
    public String nodeType;
    public String actorId;
    public String actorName;
    /** APPROVE=通过/REJECT=驳回 */
    public String action;
    public LocalDateTime at;
}
