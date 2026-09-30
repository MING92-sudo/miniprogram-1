package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
@Data


/** 电梯档案（平台 2.7 回填字段 + 本地维护字段 + 维保绑定配置） */
@TableName("elevator")
public class Elevator {
    @TableId
    public String id;
    public String elevatorCode;
    public String elevatorName;
    public String location;
    public String regCode;
    public String deviceCode;
    public String insideNumber;
    public String model;
    public String useUnitId;
    public String category;
    public LocalDate nextCheckDate;
    public LocalDate nextMaintenanceDate;
    public String factoryNumber;
    public String useUnitEntityId;
    public String elevatorAdminister;
    public String elevatorAdministerPhone;
    public String emergencyPhone;
    public LocalDateTime platformSyncedAt;
    public BigDecimal lng;
    public BigDecimal lat;
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
    public Integer intervalDays;
    public String workerName;
    public String workerPhone;
    public String workerPlatformId;
    public String assistantName;
    public String assistantPlatformId;
    public LocalDateTime lastMaintenanceAt;
}
