package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("biz_export_approval")
public class BizExportApproval {
    @TableId
    public String id;
    public String applicant;
    public String purpose;
    public String scope;
    public String status;
    public String approvedBy;
    public LocalDateTime createdAt;
    public LocalDateTime approvedAt;
}