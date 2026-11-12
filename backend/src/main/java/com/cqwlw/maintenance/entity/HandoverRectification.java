package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 移交整改记录（Task 4）：安装部上传整改前后照片 + 原查验工程师复验结果 */
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
}
