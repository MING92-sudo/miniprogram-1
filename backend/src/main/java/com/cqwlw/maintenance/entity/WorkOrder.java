package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
@Data


/** 维保工单；checklist 以 JSON 列存储（懒加载由 ChecklistService 按模板生成） */
@TableName("work_order")
public class WorkOrder {
    @TableId
    public String id;
    public String orderNo;
    public String elevatorId;
    public String workType;
    public String workTypeCode;
    public LocalDateTime planTime;
    public String status;
    public String workerName;
    public String assistantName;
    public String workerPlatformId;
    public String assistantPlatformId;
    public LocalDateTime checkinTime;
    public LocalDateTime checkoutTime;
    public String duration;
    public String originalRecordId;
    public String reportStatus;
    public Boolean autoDispatched;
    @TableField("checkin_extra")
    public String checkinExtraJson;
    @TableField("assistant_checkin_extra")
    public String assistantCheckinExtraJson;
    /** 双人动态码（主维保生成，60 秒有效、一次性； */
    public String dynamicCode;
    public LocalDateTime dynamicCodeExpiresAt;
    public String checklistJson;
    /** 只存 fileId 不带签名 URL） */
    public String workerSignatureFileId;
    public LocalDateTime workerSignatureAt;
    public String assistantSignatureFileId;
    public LocalDateTime assistantSignatureAt;
    public String sourceHandoverId;
    public Boolean handoverIssue;
    public Integer slaMinutes;
    public LocalDateTime deadline;
    public String useUnitId;
    public String useUnitName;
    public String regionId;
    public Boolean duoRequired;
}
