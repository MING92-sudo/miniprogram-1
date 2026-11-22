package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("handover_archive")
public class HandoverArchive {
    @TableId
    public String id;
    public String handoverId;
    public String fileId;
    public String category;
    public Integer seq;
    public String sha256;
    public LocalDateTime archivedAt;
}