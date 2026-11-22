package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_elevator_change")
public class BizElevatorChange {
    @TableId
    public String id;
    public String elevatorId;
    public String changeType;
    public String initiatorId;
    public String confirmerId;
    public String reason;
    public String beforeSnap;
    public String afterSnap;
    public String status;
    public LocalDateTime createdAt;
    public LocalDateTime confirmedAt;
}