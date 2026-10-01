package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
@Data


/** 幂等键（X-Idempotency-Key）：存响应，重放直接返回原结果 */
@TableName("idempotency_key")
public class IdempotencyKey {
    @TableId
    public String idemKey;
    public String path;
    public String responseJson;
    public LocalDateTime createdAt;
}
