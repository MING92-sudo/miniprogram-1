package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
@Data


/** 困人救援（超时记录不可删除） */
@TableName("rescue")
public class Rescue {
    @TableId
    public String id;
    public String elevatorCode;
    public Integer trappedCount;
    public String descr;
    public LocalDateTime alarmAt;
    public LocalDateTime departAt;
    public LocalDateTime arriveAt;
    public LocalDateTime rescuedAt;
    public Integer arriveMinutes;
    public Integer rescuedMinutes;
    public Boolean overtime;
    public String reason;
    public String action;
    public String status;
    public LocalDateTime createdAt;
}
