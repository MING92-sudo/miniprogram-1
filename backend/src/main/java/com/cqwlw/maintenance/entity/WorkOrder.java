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
    /** 列名与实体字段不可由驼峰推导（V2 建的是 checkin_extra，不是 checkin_extra_json） */
    @TableField("checkin_extra")
    public String checkinExtraJson;
    /** 配合人员签到留痕（与 checkin_extra 分开，双人各自一条，V10 迁移） */
    @TableField("assistant_checkin_extra")
    public String assistantCheckinExtraJson;
    /** 双人动态码（主维保生成，60 秒有效、一次性；空串=已使用，V10 迁移） */
    public String dynamicCode;
    public LocalDateTime dynamicCodeExpiresAt;
    public String checklistJson;
    /** 主维保签字 fileId（各自手机签，V23 迁移；只存 fileId 不带签名 URL） */
    public String workerSignatureFileId;
    public LocalDateTime workerSignatureAt;
    /** 配合人员签字 fileId（V23 迁移） */
    public String assistantSignatureFileId;
    public LocalDateTime assistantSignatureAt;
    /** 首保来源移交单（可空，V25 迁移） */
    public String sourceHandoverId;
    /** 首保来源外接建档单（可空，V25 迁移） */
    public String sourceFilingId;
    /** 移交遗留问题勾选（默认 false，首保签退回写来源移交单，V25 迁移） */
    public Boolean handoverIssue;
    /** M4 SLA 快照（创建时冻结） */
    public Integer slaMinutes;
    public LocalDateTime deadline;
    /** P2 租户快照（V47）：派单时冻结的租户归属/单位名称 */
    public String useUnitId;
    public String useUnitName;
    /** 区域池快照（派单时取电梯 regionId，2026-11-20 区域抢单池设计，V57 迁移） */
    public String regionId;
    /** 新工单恒双人；NULL 仅表示 V63 前的历史存量工单（V63 迁移） */
    public Boolean duoRequired;
}
