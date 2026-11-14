package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 角色默认数据范围（sys_role_scope）：6 范围枚举 + version。 */
@Data
@TableName("sys_role_scope")
public class SysRoleScope {
    @TableId
    public String roleCode;
    public String scope;
    public String scopeRef;
    public Integer version;
}