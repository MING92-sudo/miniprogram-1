package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 维保合同（最小版，Task 3：外接建档 W2 的前置——外接建档须关联已生效合同） */
@TableName("maintenance_contract")
@Data
public class MaintenanceContract {
    @TableId
    public String id;
    public String contractNo;
    public String useUnitId;
    public LocalDate startDate;
    public LocalDate endDate;
    public String status;
    public BigDecimal amount;
}
