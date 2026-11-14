package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 移交归档目录 + SHA-256（C-12，4 年留存）。 */
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