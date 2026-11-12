package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 接管查验项（Task 7）：FAIL 必须写 responsibility（前维保单位/使用单位/我方接管后） */
@TableName("external_takeover_check_item")
@Data
public class ExternalTakeoverCheckItem {
    @TableId
    public String id;
    public String checkId;
    public String itemType;
    /** PASS/FAIL/NA */
    public String result;
    /** PREV_MAINT/USE_UNIT/OURS_AFTER_TAKEOVER（FAIL 必填） */
    public String responsibility;
    public String remark;
}
