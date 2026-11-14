package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 业务配置（sys_config，M5）。 */
@Data
@TableName("sys_config")
public class SysConfig {
    @TableId
    public String id;
    public String cfgKey;
    public String cfgValue;
    public String updatedBy;
    public LocalDateTime updatedAt;
}