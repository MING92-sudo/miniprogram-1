package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 延期申请与审批（docs/04 A.5：POST /plans/{id}/delay；审批 ADMIN） */
@Data
@TableName("plan_delay")
public class PlanDelay {
    @TableId
    public String id;
    public String planId;
    public String reason;
    public Integer delayDays;
    public LocalDate expectedDate;
    /** PENDING/APPROVED/REJECTED */
    public String status;
    public String applicantId;
    public String approverId;
    public String approveComment;
    /** 0=平台侧 nextMaintenanceDate 已固化无法修改（A.5 约束提示） */
    public Boolean platformDateSynced;
    public LocalDateTime createdAt;
    public LocalDateTime decidedAt;
}
