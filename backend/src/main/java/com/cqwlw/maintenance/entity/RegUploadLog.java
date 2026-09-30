package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台上报日志（reg_upload_log）：每次 2.6/2.8 转发落一条，
 * request_digest 脱敏（AGENTS §2.4：不打印完整 token、手机号、密钥）。
 */
@Data
@TableName("reg_upload_log")
public class RegUploadLog {
    @TableId
    public String id;
    public String originalRecordId;
    /** UPLOAD=签退自动上报；REUPLOAD=手动重报；LEGACY=2.8 存量 */
    public String action;
    public String requestDigest;
    public String platformCode;
    public String platformMessage;
    public String status;
    public LocalDateTime createdAt;
}
