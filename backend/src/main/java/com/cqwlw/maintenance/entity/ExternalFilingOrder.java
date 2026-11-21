package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 外接建档单（W2，Task 7）：使用单位从其它维保单位转入时的建档路径。
 * 5 态：INIT→DOC_COLLECTING→TAKEOVER_CHECKING→PENDING_CONFIRM→ARCHIVED；
 * 前置=已生效合同（contract_id），归档副作用（建电梯 archive_source=2 + 首保 planTime=归档日+3 日）在 archive()。
 */
@TableName("external_filing_order")
@Data
public class ExternalFilingOrder {
    @TableId
    public String id;
    public String filingNo;
    /** 发起时间（V33；管理端列表排序用） */
    public LocalDateTime createdAt;
    /** INIT/DOC_COLLECTING/TAKEOVER_CHECKING/PENDING_CONFIRM/ARCHIVED */
    public String status;
    public String contractId;
    public String useUnitId;
    /** 归档建梯后回填（1:1） */
    public String elevatorId;
    public String initiatorId;
    public String initiatorName;
    public String confirmedById;
    public String confirmedByName;
    public LocalDateTime confirmedAt;
    /** 电梯信息快照列（供 archive() 归档建电梯用，类型照 elevator 对应列） */
    public String projectName;
    public LocalDate supervisionPassDate;
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
    /** 出厂编号（原 productNo，V59 列改名 factory_number；出厂编号=产品编号统一口径） */
    public String factoryNumber;
    public String driveMode;
    public Integer ratedLoad;
    public String ratedLoadUnit;
    public BigDecimal ratedSpeed;
    public String ratedSpeedUnit;
    public String stationsDoors;
    public String workTypeCode;
}
