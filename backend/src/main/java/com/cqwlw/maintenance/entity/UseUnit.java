package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
@Data


/** 使用单位档案 */
@TableName("use_unit")
public class UseUnit {
    @TableId
    public String id;
    public String unitName;
    public String organizationCode;
    public String unitPrincipal;
    public String unitPrincipalPhone;
    public String elevatorAdminister;
    public String elevatorAdministerPhone;
    public String emergencyPhone;
    public String entityId;
    /** 门户登录账号 / 口令哈希 / 邀请码 / 绑定 openid（V34 迁移，Task 9） */
    public String account;
    public String passwordHash;
    public String inviteCode;
    public String openid;
    public java.time.LocalDateTime boundAt;
    /** 门户账号并入 sys_employee 标记（V41，M1）：0=未迁移（跨部门兜底），1=已迁移 */
    public Integer accountMigrated;
}
