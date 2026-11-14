package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户-角色多对多（sys_user_role）：同部门兼任允许、跨部门禁止（R7）。 */
@Data
@TableName("sys_user_role")
public class SysUserRole {
    @TableId
    public String id;
    public String userId;
    public String userType;
    public String roleCode;
    public String orgId;
    public LocalDateTime createdAt;
}