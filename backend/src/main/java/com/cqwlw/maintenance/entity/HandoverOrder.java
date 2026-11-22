package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("handover_order")
@Data
public class HandoverOrder {
    @TableId
    public String id;
    public String handoverNo;
    public LocalDateTime createdAt;
    /** INIT/DOC_SUBMITTING/INSPECTING/RECTIFYING/PENDING_CONFIRM/ARCHIVED */
    public String status;
    /** 归档建梯后回填（1:1） */
    public String elevatorId;
    public Boolean handoverIssue;
    public String projectName;
    /** 使用单位档案引用，归档时复制到 elevator.useUnitId，保证联系人回退口径一致。 */
    public String useUnitId;
    public LocalDate supervisionPassDate;
    /** 发起人=安装部项目经理 */
    public String initiatorId;
    public String initiatorName;
    /** 接收工程师（assignReceiver 写） */
    public String receiverId;
    public String receiverName;
    /** 3 必传资料：监督检验报告 / 维保说明书 / 整机质量证明 */
    public String supervisionReportFileId;
    public String maintenanceManualFileId;
    public String qualityCertificateFileId;
    /** 确认人（维保部经理，confirm 写） */
    public String confirmedById;
    public String confirmedByName;
    public LocalDateTime confirmedAt;
    public String elevatorCode;
    public String elevatorName;
    public String category;
    public String location;
    public String regCode;
    public String deviceCode;
    public String insideNumber;
    public String model;
    public String brand;
    public String manufacturer;
    public String factoryNumber;
    public String driveMode;
    public Integer ratedLoad;
    public String ratedLoadUnit;
    public BigDecimal ratedSpeed;
    public String ratedSpeedUnit;
    public String stationsDoors;
    public String workTypeCode;
    public String installCompleteDate;
    public String installUnitName;
    public String specialType;
    public String reviewById;
    public String reviewByName;
    public LocalDateTime reviewedAt;
    public String reviewRemark;
    public String reviewDeadline;
    public String voidReason;
    public String voidById;
    public String voidByName;
    public LocalDateTime voidedAt;
}
