package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.Fault;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface FaultMapper extends BaseMapper<Fault> {

    @Update("UPDATE fault SET dispatch_worker_id = #{empId}, dispatched_at = #{dispatchedAt}, status = 'ASSIGNED' "
            + "WHERE id = #{id} AND status = 'OPEN'")
    int claimOpen(@Param("id") String id, @Param("empId") String empId,
                  @Param("dispatchedAt") LocalDateTime dispatchedAt);

    @Update("UPDATE fault SET status = 'CLOSED', confirmed_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'PENDING_CONFIRM'")
    int closePending(@Param("id") String id);

    @Update("UPDATE fault SET status = 'REFUSED', refuse_reason = #{reason} "
            + "WHERE id = #{id} AND status = 'PENDING_CONFIRM'")
    int refusePending(@Param("id") String id, @Param("reason") String reason);
}
