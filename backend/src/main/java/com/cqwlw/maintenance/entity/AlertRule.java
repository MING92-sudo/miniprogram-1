package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 预警规则：9 类预警，提前天数与启停可配 */
@Data
@TableName("alert_rule")
public class AlertRule {
    @TableId
    public String id;
    public String type;
    public String name;
    /** 提前天数；负值 = 逾期后提醒 */
    public Integer advanceDays;
    public String target;
    public Boolean enabled;
    public LocalDateTime updatedAt;
}
