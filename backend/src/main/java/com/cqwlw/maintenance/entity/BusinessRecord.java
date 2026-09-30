package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** MVP 期救援、故障、演练、自行检查等低频台账统一持久化；后续按 docs/02 拆表。 */
@Data
@TableName("biz_record")
public class BusinessRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String type;
    private String status;
    private String contentJson;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
