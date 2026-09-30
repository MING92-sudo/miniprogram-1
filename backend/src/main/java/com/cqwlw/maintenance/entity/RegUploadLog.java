package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("reg_upload_log")
public class RegUploadLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String originalRecordId;
    private String api;
    private String requestBody;
    private String responseBody;
    private Boolean success;
    private Integer retryCount;
    private Integer httpStatus;
    private String platformCode;
    private Long costMs;
    private LocalDateTime createdAt;
}
