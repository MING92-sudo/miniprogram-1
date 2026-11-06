package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.WorkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

    /**
     * 消费双人动态码的原子条件更新（docs/05 §2.5 一次性使用）：
     * 仅当该工单尚未消费过动态码（NULL 或空串）时写入，命中 0 行即表示已被并发消费。
     * 替代「内存判空 + updateById」两步的 TOCTOU——并发下同一码会各自判空通过、互相覆盖。
     */
    @Update("UPDATE work_order SET dynamic_code = #{code}, dynamic_code_expires_at = #{expiresAt} "
            + "WHERE id = #{id} AND (dynamic_code IS NULL OR dynamic_code = '')")
    int consumeDynamicCodeIfUnused(@Param("id") String id,
                                   @Param("code") String code,
                                   @Param("expiresAt") LocalDateTime expiresAt);
}
