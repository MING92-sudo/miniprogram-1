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
    public String openid;
    public String platformId;
    public String certificate;
    public String workStartDate;
    public String workEndDate;
    public String workStat;
    public String syncStatus;

    @TableField(exist = false)
    public String password;
}
