package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
@Data


/** 自行检查（每台每年至少 1 次，须在下次定期检验前完成） */
@TableName("inspect_record")
public class InspectRecord {
    @TableId
    public String id;
    public String elevatorId;
    public LocalDate inspectDate;
    public Integer itemTotal;
    public Integer abnormalCount;
    public String problems;
    public String inspectorSign;
    public String reviewerSign;
}
