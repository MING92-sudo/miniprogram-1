package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 合同电梯关联（Task 3）：contract_id + elevator_id 唯一 */
@TableName("contract_elevator")
@Data
public class ContractElevator {
    @TableId
    public String id;
    public String contractId;
    public String elevatorId;
}
