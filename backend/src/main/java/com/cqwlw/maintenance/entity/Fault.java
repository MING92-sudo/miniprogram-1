package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("fault")
@Data
public class Fault {
    @TableId
    public String id;
    public String elevatorCode;
    public String faultType;
    public String descr;
    public String status;
    public String handleDesc;
    public LocalDateTime createdAt;
}
