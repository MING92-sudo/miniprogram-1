package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@TableName("knowledge")
@Data
public class Knowledge {
    @TableId
    public String id;
    public String title;
    public String tag;
    public String content;
}
