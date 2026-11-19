package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户账号（P3，docs/14 §8）：独立租户主体，脱离 sys_employee。
 * 一个使用单位 1..N 个账号（默认 1 个 CLIENT_ADMIN）；同一单位内活跃手机号/openid 唯一，
 * 跨单位可多账号。客户账号不进入员工组织树/统计（R7），签字动作记快照（R13）。
 */
@Data
@TableName("client_account")
public class ClientAccount {
    @TableId
    public String id;
    public String useUnitId;
    public String name;
    public String phone;
    public String passwordHash;
    public String openid;
    /** CLIENT_ADMIN / CLIENT_SIGNER（规范角色；主体类型恒 CUSTOMER） */
    public String roleCode;
    /** ACTIVE / GRACE / DISABLED */
    public String status;
    public LocalDateTime graceUntil;
    /** 兼容旧字段；status 为事实源 */
    public Integer enabled;
    public String sessionId;
    public Integer scopeVersion;
    public String createdBy;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public LocalDateTime disabledAt;
    /** MANUAL_* / GRACE_EXPIRED / MIGRATED_* */
    public String disabledReason;
    public LocalDateTime lastLoginAt;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_GRACE = "GRACE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String ROLE_ADMIN = "CLIENT_ADMIN";
    public static final String ROLE_SIGNER = "CLIENT_SIGNER";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status) || STATUS_GRACE.equals(status);
    }
}
