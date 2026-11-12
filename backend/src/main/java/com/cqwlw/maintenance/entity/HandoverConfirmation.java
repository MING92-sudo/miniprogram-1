package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 交接确认单（Task 7）：钥匙工具核对 + 遗留问题 + 双方签字（我方账号节点通过 + 对方手写签名） */
@TableName("handover_confirmation")
@Data
public class HandoverConfirmation {
    @TableId
    public String id;
    public String filingId;
    public String keyToolCheck;
    public String leftoverIssue;
    public String ourSignFileId;
    public String counterpartSignName;
    /** 对方手写签名图（必填） */
    public String counterpartSignFileId;
    public String confirmedById;
    public String confirmedByName;
    public LocalDateTime at;
}
