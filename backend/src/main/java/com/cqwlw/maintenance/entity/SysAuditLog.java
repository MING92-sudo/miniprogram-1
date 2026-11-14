package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 业务审计（sys_audit_log）：只读不可删改；result∈SUCCESS/FAILED/BLOCKED。 */
@Data
@TableName("sys_audit_log")
public class SysAuditLog {
    @TableId
    public String id;
    public String actorId;
    public String actorRole;
    public String action;
    public String target;
    public String orgId;
    public String projectId;
    public String beforeSnap;
    public String afterSnap;
    public String hashRef;
    public String reason;
    public String device;
    public String ip;
    public String gps;
    public String result;
    public LocalDateTime createdAt;
}