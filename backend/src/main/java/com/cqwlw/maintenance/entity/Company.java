package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("company")
public class Company {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String organizationCode;
    private String name;
    private String workManagerName;
    private String workManagerPhone;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
