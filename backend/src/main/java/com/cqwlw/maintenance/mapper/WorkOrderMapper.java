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

    /**
     * 区域抢单池认领（2026-11-20 设计 §4.2 / M1）：主维保首次签到时原子认领工单。
     * 仅当工单未认领（worker_platform_id 空）且仍为 PENDING 时写入，命中 0 行即已被并发认领——
     * 与 {@link #consumeDynamicCodeIfUnused} 同一并发防护范式（判空+写入单条 SQL，杜绝 TOCTOU）；
     * status 不在这里翻转，认领成功后由签到主流程统一置 PROCESSING。
     */
    @Update("UPDATE work_order SET worker_name = #{name}, worker_platform_id = #{pid} "
            + "WHERE id = #{id} AND (worker_platform_id IS NULL OR worker_platform_id = '') "
            + "AND status = 'PENDING'")
    int claimOrder(@Param("id") String id,
                   @Param("name") String name,
                   @Param("pid") String pid);
}
