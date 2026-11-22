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
    /** 所属班组（组长按班组查看组员工单/急修单，V7） */
    public String groupName;
    /** 所属区域（region.id，可空；移交/使用单位门户可见性范围谓词，V24 迁移） */
    public String regionId;
    /** 部门：INSTALL/MAINT/UNIT/PLATFORM（docs/14 §4 部门域互斥，V31 迁移；为空时按 role 回退） */
    public String department;
    /** 直属上级 empId（docs/14 §5 上级可见下级，V31 迁移） */
    public String managerId;
    /** 岗位名称（展示用，V31 迁移） */
    public String jobTitle;
    /** 角色/范围变更递增→旧 token 失效（M1，V35 迁移） */
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
