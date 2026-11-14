package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 限时授权（sys_grant）：PENDING/ACTIVE/REVOKED/WITHDRAWN，到期自动回收。 */
@Data
@TableName("sys_grant")
public class SysGrant {
    @TableId
    public String id;
    public String granteeId;
    public String permCode;
    public String scope;
    public String scopeRef;
    public LocalDateTime startAt;
    public LocalDateTime endAt;
    public String status;
    public String grantedBy;
    public String reason;
    public LocalDateTime createdAt;
}