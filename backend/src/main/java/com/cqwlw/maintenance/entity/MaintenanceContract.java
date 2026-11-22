package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 维保合同（项目合同台账：使用单位、生效期、金额及电梯关联） */
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
