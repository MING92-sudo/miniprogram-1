package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 定位异常申述（1001 → POST /workorders/{id}/location-appeal → 管理端审核） */
@Data
@TableName("location_appeal")
public class LocationAppeal {
    @TableId
    public String id;
    public String workOrderId;
    public String employeeId;
    public String reason;
    public String photoKey;
    public BigDecimal distance;
    public BigDecimal threshold;
    public String status;
    public String reviewerId;
    public String reviewComment;
    public LocalDateTime reviewedAt;
    public LocalDateTime createdAt;
}
