package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;
@Data


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
