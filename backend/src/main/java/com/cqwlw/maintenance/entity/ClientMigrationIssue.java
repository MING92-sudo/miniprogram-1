package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 幂等 upsert）。 */
@Data
@TableName("client_migration_issue")
public class ClientMigrationIssue {
    @TableId
    public String id;
    public String issueType;
    public String objectType;
    public String objectId;
    public String elevatorCode;
    public String currentUnitId;
    public String candidateUnitId;
    public String evidence;
    public String confidence;
    public String resolution;
    /** 人工裁定 JSON（DEDUP_CONFLICT 取源/NO_ANCHOR 补锚点），Runner 重跑消费（spec §19-15） */
    public String decisionJson;
    public String resolveNote;
    public String resolvedBy;
    public LocalDateTime resolvedAt;
    public LocalDateTime createdAt;
}
