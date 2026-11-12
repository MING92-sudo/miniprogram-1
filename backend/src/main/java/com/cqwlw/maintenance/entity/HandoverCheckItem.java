package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 移交查验单项（Task 4）：6 类 item_type，result=PASS/FAIL/NA */
@TableName("handover_check_item")
@Data
public class HandoverCheckItem {
    @TableId
    public String id;
    public String checkId;
    /** RUNNING/SAFETY_DEVICE/BRAKE/EMERGENCY_OPERATION/MACHINE_ROOM_SHAFT/ACCESSORIES */
    public String itemType;
    /** PASS/FAIL/NA */
    public String result;
    public String remark;
}
