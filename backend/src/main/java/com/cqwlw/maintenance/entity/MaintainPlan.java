package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("maintain_plan")
public class MaintainPlan {
    @TableId
    public String id;
    public String elevatorId;
    public LocalDate planDate;
    public String workTypeCode;
    /** UNASSIGNED/ASSIGNED/POSTPONE_PENDING/DISPATCHED/CANCELLED */
    public String status;
    public String principalId;
    public String assistantId;
    /** 指派后生成的工单 */
    public String orderId;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
