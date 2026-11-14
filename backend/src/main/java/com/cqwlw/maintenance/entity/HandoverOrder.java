package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 移交单（W1，Task 4）：安装部 → 维保部的新装电梯移交。
 * 6 态状态机 INIT→DOC_SUBMITTING→INSPECTING→(RECTIFYING⇄)→PENDING_CONFIRM→ARCHIVED；
 * 归档副作用（建电梯/首保工单）在 Task 5 的 archive()，本实体只承载状态机与电梯信息快照。
 */
@TableName("handover_order")
@Data
public class HandoverOrder {
    @TableId
    public String id;
    public String handoverNo;
    /** 发起时间（V33；管理端列表排序用） */
    public LocalDateTime createdAt;
    /** INIT/DOC_SUBMITTING/INSPECTING/RECTIFYING/PENDING_CONFIRM/ARCHIVED */
    public String status;
    /** 归档建梯后回填（1:1） */
    public String elevatorId;
    /** 首保签退回写的遗留问题标记（默认 false，V28 迁移） */
    public Boolean handoverIssue;
    public String projectName;
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
    /** 电梯信息快照列（供 Task 5 archive() 归档建电梯用，类型照 elevator 对应列） */
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
    public String productNo;
    public String driveMode;
    public Integer ratedLoad;
    public String ratedLoadUnit;
    public BigDecimal ratedSpeed;
    public String ratedSpeedUnit;
    public String stationsDoors;
    public String workTypeCode;
    /** C-12：表单 §3.3 第 6—7 行 + 特殊类别 */
    public String installCompleteDate;
    public String installUnitName;
    public String specialType;
}
