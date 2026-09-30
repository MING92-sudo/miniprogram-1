package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("elevator")
public class Elevator {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String elevatorCode;
    private String elevatorName;
    private String deviceCode;
    private String registrationCode;
    private String insideNumber;
    private Long useUnitId;
    private String category;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private LocalDate nextCheckDate;
    private String brand;
    private String manufacturer;
    private String productNo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
