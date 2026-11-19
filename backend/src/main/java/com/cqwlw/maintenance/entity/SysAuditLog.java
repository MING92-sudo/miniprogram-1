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
    /** P3（V49）：客户租户 id；内部审计为空，不复用 org_id */
    public String useUnitId;
    public LocalDateTime createdAt;
}