package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("work_order")
public class WorkOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long elevatorId;
    private String workType;
    private String workTypeCode;
    private LocalDateTime planTime;
    private String status;
    private String workerName;
    private String assistantName;
    private String workerPhone;
    private String assistantPhone;
    private String workerPlatformId;
    private String assistantPlatformId;
    private LocalDateTime checkinTime;
    private LocalDateTime checkoutTime;
    private String duration;
    private String originalRecordId;
    private String reportStatus;
    private String checklistJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
