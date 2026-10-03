package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 系统参数（docs/02 §5.4：签到定位阈值的全局/品种级配置载体，V12 迁移） */
@Data
@TableName("sys_param")
public class SysParam {
    @TableId
    public String paramKey;
    public String paramValue;
    public String remark;
    public LocalDateTime updatedAt;
}
