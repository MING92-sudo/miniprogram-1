package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("maintain_record")
public class MaintainRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workOrderId;
    private String originalRecordId;
    private String elevatorCode;
    private String deviceCode;
    private String workType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String reportStatus;
    private String platformMessage;
    private String snapshotJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
