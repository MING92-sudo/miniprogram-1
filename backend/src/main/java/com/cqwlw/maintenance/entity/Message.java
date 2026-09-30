package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("message")
@Data
public class Message {
    @TableId
    public String id;
    public String title;
    public String content;
    public LocalDateTime createdAt;
    public Boolean readFlag;
}
