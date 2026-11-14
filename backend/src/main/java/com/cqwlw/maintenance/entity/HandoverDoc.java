package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 移交资料核对 D1—D20（C-12）。 */
@Data
@TableName("handover_doc")
public class HandoverDoc {
    @TableId
    public String id;
    public String handoverId;
    public Integer docNo;
    public String docName;
    public Boolean required;
    public String fileId;
    public String naReason;
    public LocalDateTime uploadedAt;
}