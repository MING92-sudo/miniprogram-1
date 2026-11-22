package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("region")
@Data
public class Region {
    @TableId
    public String id;
    public String name;
    public Integer sortOrder;
    public String remark;
}
