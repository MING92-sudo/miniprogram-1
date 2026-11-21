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
    /** 受众使用单位（V56）：NULL=员工消息中心；非空=该单位客户门户可见 */
    public String useUnitId;
}
