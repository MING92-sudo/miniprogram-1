package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

/** 区域主数据（移交/使用单位门户的可见性范围谓词，Task 1） */
@TableName("region")
@Data
public class Region {
    @TableId
    public String id;
    public String name;
    public Integer sortOrder;
    public String remark;
}
