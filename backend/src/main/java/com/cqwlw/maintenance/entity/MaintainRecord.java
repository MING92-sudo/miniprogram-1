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
    /** 安全防护确认勾选（JSON：warning/barrier/powerOff/twoPerson，V20） */
    public String safetyJson;
    /** 待办事项（V20；空=无待办） */
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
    /** 预览上下文快照 JSON（地址/单位/手机号/签到经纬度/条目数，docs/03 §六，V11 迁移） */
    @TableField("preview_context")
    public String previewContextJson;
    /** M4 版本链：修订生成 v2 + 新哈希，v1 与签名永久保留 */
    public Integer version;
    public String prevVersionId;
    public String amendReason;
    public String amendedBy;
    public LocalDateTime amendedAt;
    /** 存量单人豁免（P-4），新工单恒0 */
    public Integer soloExempt;
    /** 记录生命周期（V42，M1，蓝图 §6.2）：PENDING_REVIEW/PENDING_CLIENT_SIGN/PENDING_CLIENT_SIGNATURE/SIGNED/ARCHIVED/REFUSED_RETURNED */
    public String lifecycleStatus;
    public String workOrderId;
    public String refuseReason;
    public LocalDateTime archivedAt;
    public String archiveFileId;
    public String archiveHash;
    public LocalDateTime createdAt;
}
