package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;
@Data


/** 维保记录（签退冻结快照；即"使用单位待确认记录"，P3 上报来源） */
@TableName("maintain_record")
public class MaintainRecord {
    @TableId
    public String id;
    public String elevatorName;
    public String elevatorCode;
    public String workType;
    public String workTypeCode;
    public String workerName;
    public String assistantName;
    public String workerPlatformId;
    public String assistantPlatformId;
    public LocalDateTime checkinTime;
    public LocalDateTime checkoutTime;
    public String duration;
    public String itemsJson;
    public String photosJson;
    public String workerSignatureUrl;
    public String assistantSignatureUrl;
    public String problemCodesJson;
    public String originalRecordId;
    public String reportStatus;
    public Integer retryCount;
    public LocalDate nextMaintenanceDate;
    public String confirmStatus;
    public Integer satisfaction;
    public String signatureFileId;
    public String signatureUrl;
    public String shareToken;
    public String reportPayloadJson;
    /** 预览上下文快照 JSON（地址/单位/手机号/签到经纬度/条目数，docs/03 §六，V11 迁移） */
    @TableField("preview_context")
    public String previewContextJson;
    public LocalDateTime createdAt;
}
