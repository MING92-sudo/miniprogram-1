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
    /** 登记人（empId；组长按班组可见，V7） */
    public String createdBy;
    public String descr;
    public String status;
    public String handleDesc;
    public String photos;
    public String signature;
    public LocalDateTime confirmedAt;
    public LocalDateTime createdAt;
}
