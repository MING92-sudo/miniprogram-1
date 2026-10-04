package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("fault")
@Data
public class Fault {
    @TableId
    public String id;
    /** 单号：BWJX + yyyyMMddHHmm + 3 位当日顺序 = 19 位 */
    public String faultNo;
    public String elevatorCode;
    public String faultType;
    /** 登记人（empId；组长按班组可见，V7） */
    public String createdBy;
    public String descr;
    public String siteDesc;
    public String status;
    public String handleDesc;
    /** 到场时间：以维保人员当日首次签到为准 */
    public java.time.LocalDateTime arrivedAt;
    /** 维修结束时间 */
    public java.time.LocalDateTime finishedAt;
    public String todoDesc;
    public String photos;
    public String signature;
    public LocalDateTime confirmedAt;
    public LocalDateTime createdAt;
}
