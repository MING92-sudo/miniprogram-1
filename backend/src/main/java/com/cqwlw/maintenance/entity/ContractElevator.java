package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@TableName("contract_elevator")
@Data
public class ContractElevator {
    @TableId
    public String id;
    public String contractId;
    public String elevatorId;
}
