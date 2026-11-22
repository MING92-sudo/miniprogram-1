package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
@Data


/** 员工/演示账号（password 只存 BCrypt 哈希） */
@TableName("sys_employee")
public class Employee {
    @TableId
    public String id;
    public String name;
    public String phone;
    public String account;
    public String passwordHash;
    public String role;
    public String roleText;
    public String groupName;
    public String regionId;
    public String department;
    public String managerId;
    public String jobTitle;
    public Integer scopeVersion;
    public String openid;
    public String platformId;
    public String certificate;
    public String workStartDate;
    public String workEndDate;
    public String workStat;
    public String syncStatus;
    /** 当前有效会话（单端登录：登录时覆盖，拦截器比对不符即 401） */
    public String sessionId;
    /** 管理端（web）当前有效会话；与小程序会话互不干扰 */
    public String sessionAdmin;
    /** 平台绑定状态（2.4 changState：0 建立/正常，1 中止） */
    public Integer bindStatus;
    /** 账号启用（SYS_ADMIN 管理，停用后无法登录） */
    public Boolean enabled;

    @TableField(exist = false)
    public String password;
}
