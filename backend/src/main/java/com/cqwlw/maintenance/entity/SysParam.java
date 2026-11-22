package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_param")
public class SysParam {
    @TableId
    public String paramKey;
    public String paramValue;
    public String remark;
    public LocalDateTime updatedAt;
}
