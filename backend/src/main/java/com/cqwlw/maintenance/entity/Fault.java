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
    public Integer trappedCount;
    public String createdBy;
    public String descr;
    public String siteDesc;
    public String status;
    public String dispatchWorkerId;
    /** 派单时间 */
    public java.time.LocalDateTime dispatchedAt;
    public String handleDesc;
    /** 到场时间：以维保人员当日首次签到为准 */
    public java.time.LocalDateTime arrivedAt;
    /** 维修结束时间 */
    public java.time.LocalDateTime finishedAt;
    public String todoDesc;
    public String photos;
    public String signature;
    public LocalDateTime confirmedAt;
    public String proxySource;
    public String recordingNo;
    public String proxyBy;
    public Integer slaMinutes;
    public LocalDateTime deadline;
    public String faultReason;
    public String cancelReason;
    public String refuseReason;
    public Integer evaluateScore;
    public String evaluateText;
    public LocalDateTime evaluatedAt;
    public LocalDateTime escalatedAt;
    public String useUnitId;
    public String useUnitName;
    public LocalDateTime createdAt;
}
