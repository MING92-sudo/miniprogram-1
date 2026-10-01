package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 预警记录（docs/04 A.6 GET /alerts；OPEN/RESOLVED） */
@Data
@TableName("alert_record")
public class AlertRecord {
    @TableId
    public String id;
    public String ruleType;
    public String title;
    public String content;
    public String target;
    public String status;
    public Boolean readFlag;
    public LocalDateTime createdAt;
}
