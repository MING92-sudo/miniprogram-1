package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_project")
public class BizProject {
    @TableId
    public String id;
    public String projectNo;
    public String name;
    public String useUnitId;
    public String maintOrgId;
    public String installOrgId;
    public Integer elevatorCount;
    public String contractId;
    public String contractEndDate;
    public String status;
    public String lastWorkDate;
    public LocalDateTime createdAt;
}