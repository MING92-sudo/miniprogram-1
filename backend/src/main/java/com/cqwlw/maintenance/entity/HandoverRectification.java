package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("handover_rectification")
@Data
public class HandoverRectification {
    @TableId
    public String id;
    public String handoverId;
    public String itemType;
    public String beforePhotoFileId;
    public String afterPhotoFileId;
    /** PASS/FAIL */
    public String recheckResult;
    public String recheckerId;
    public String recheckerName;
    public LocalDateTime recheckedAt;
    public Integer itemNo;
    public String respParty;
    public String dueDate;
    public String rectMeasure;
    public LocalDateTime rectifiedAt;
    public String rectifierName;
    public String source;
    public Boolean voided;
}
