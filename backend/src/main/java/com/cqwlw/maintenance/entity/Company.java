package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
@Data


/** 维保单位档案（上报冻结字段来源，workMeneger 拼写按平台原文） */
@TableName("company")
public class Company {
    @TableId
    public String id;
    public String organizationCode;
    public String name;
    public String entityId;
    public String orgId;
    public String workMenegerName;
    public String workMenegerPhone;
}
