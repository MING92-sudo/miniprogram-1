package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 组织树节点（sys_org）：公司→部门→班组；使用单位单级。 */
@Data
@TableName("sys_org")
public class SysOrg {
    @TableId
    public String id;
    public String type;
    public String name;
    public String code;
    public String parentId;
    public String leaderId;
    public String status;
    public Integer sortOrder;
    public LocalDateTime createdAt;
}