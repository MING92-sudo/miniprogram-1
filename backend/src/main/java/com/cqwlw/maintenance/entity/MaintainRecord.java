package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;
@Data


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
    public String safetyJson;
    public String todoDesc;
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
    @TableField("preview_context")
    public String previewContextJson;
    public Integer version;
    public String prevVersionId;
    public String amendReason;
    public String amendedBy;
    public LocalDateTime amendedAt;
    public Integer soloExempt;
    public String lifecycleStatus;
    public String workOrderId;
    public String refuseReason;
    public LocalDateTime archivedAt;
    public String archiveFileId;
    public String archiveHash;
    public String useUnitId;
    public String useUnitName;
    public String elevatorId;
    public String clientAccountId;
    public String clientSignerName;
    public LocalDateTime clientSignedAt;
    public String refusedByClientAccountId;
    public String clientRefuserName;
    public String clientRefuseReason;
    public LocalDateTime clientRefusedAt;
    public LocalDateTime signRequestedAt;
    public LocalDateTime signRemindedAt;
    public String refuseCode;
    public LocalDateTime createdAt;
}
