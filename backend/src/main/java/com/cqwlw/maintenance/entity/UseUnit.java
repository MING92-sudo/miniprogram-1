package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("use_unit")
public class UseUnit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String unitName;
    private String unitPrincipal;
    private String unitPrincipalPhone;
    private String elevatorAdminister;
    private String elevatorAdministerPhone;
    private String emergencyPhone;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
