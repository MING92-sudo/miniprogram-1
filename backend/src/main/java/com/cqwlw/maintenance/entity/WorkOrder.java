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
    public String checklistJson;
}
