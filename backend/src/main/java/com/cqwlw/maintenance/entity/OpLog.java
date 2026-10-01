package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 操作审计日志（全量留痕 ≥3 年，管理端只读） */
@Data
@TableName("op_log")
public class OpLog {
    @TableId
    public String id;
    public String operatorId;
    public String operatorName;
    public String method;
    public String path;
    public String action;
    public String result;
    public String ip;
    public LocalDateTime createdAt;
}
