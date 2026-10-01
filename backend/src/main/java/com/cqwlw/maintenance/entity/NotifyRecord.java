package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 订阅消息/站内消息发送记录（真实微信推送后置，本地落记录） */
@Data
@TableName("notify_record")
public class NotifyRecord {
    @TableId
    public String id;
    public String type;
    public String templateId;
    public String targetEmployeeId;
    public String targetRole;
    public String title;
    public String content;
    /** SUBSCRIBE / INBOX */
    public String channel;
    /** SENT / FAILED / PENDING */
    public String status;
    public LocalDateTime createdAt;
}
