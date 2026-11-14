package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 邀请码/组织二维码（sys_invite，M3）。 */
@Data
@TableName("sys_invite")
public class SysInvite {
    @TableId
    public String id;
    public String code;
    public String type;
    public String targetOrgId;
    public String createdBy;
    public Integer validDays;
    public LocalDateTime expiresAt;
    public String status;
    public Integer joinedCount;
    public LocalDateTime createdAt;
}