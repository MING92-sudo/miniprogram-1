package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("handover_photo")
public class HandoverPhoto {
    @TableId
    public String id;
    public String handoverId;
    public String poseCode;
    public String fileId;
    public LocalDateTime takenAt;
    public String gps;
}