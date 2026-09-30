package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;
@Data


/** 应急演练（每半年至少 1 轮，覆盖全部电梯品种，docs/01 §3.18） */
@TableName("drill")
public class Drill {
    @TableId
    public String id;
    public LocalDate drillDate;
    public String category;
    public String scene;
    public String participants;
    public String process;
    public String problems;
    public String actions;
    public LocalDateTime createdAt;
}
