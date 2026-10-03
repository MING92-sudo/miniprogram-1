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
}
