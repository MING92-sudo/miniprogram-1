package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
