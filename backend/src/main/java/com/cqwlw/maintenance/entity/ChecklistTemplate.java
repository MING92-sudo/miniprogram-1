package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 检查项模板：257 行 OFFICIAL + 消防/防爆等 CUSTOM 自定义模板 */
@Data
@TableName("checklist_template")
public class ChecklistTemplate {
    @TableId(type = IdType.AUTO)
    public Long id;
    /** OFFICIAL / CUSTOM */
    public String templateType;
    /** OFFICIAL: A/B/C/D */
    public String appendix;
    /** OFFICIAL: 品种主名；CUSTOM: 消防电梯/防爆电梯等（匹配 elevator.special_type） */
    public String categoryScope;
    /** OFFICIAL: HALF/QUARTER/HALF_YEAR/YEAR */
    public String freq;
    public String itemCode;
    public Integer seq;
    public String name;
    public String judgeType;
    public Boolean isKey;
    public Boolean photoRequired;
    public Boolean enabled;
    /** 模板条目 JSON 原文（与 checklist-template.json 逐字段一致） */
    public String payload;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
