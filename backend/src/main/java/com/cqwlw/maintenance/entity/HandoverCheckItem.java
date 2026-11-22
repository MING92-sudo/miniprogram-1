package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

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
    public Integer itemNo;
    public String itemGroup;
    public String clauseNo;
    public String itemName;
    public String requirement;
    public Boolean important;
    public String measuredValue;
    public String failDesc;
    public String naReason;
    public String naNote;
    public String photoFileIds;
}
