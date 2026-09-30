package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("app_file")
@Data
public class AppFile {
    @TableId
    public String id;
    public String objectKey;
    public String url;
    public String contentType;
    public Long sizeBytes;
    public LocalDateTime createdAt;
}
