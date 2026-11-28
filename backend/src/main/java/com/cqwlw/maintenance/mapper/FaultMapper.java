package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.Fault;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FaultMapper extends BaseMapper<Fault> {

    @Update("UPDATE fault SET dispatch_worker_id = #{empId}, dispatched_at = #{dispatchedAt}, status = 'ASSIGNED' "
            + "WHERE id = #{id} AND status = 'OPEN'")
    int claimOpen(@Param("id") String id, @Param("empId") String empId,
                  @Param("dispatchedAt") LocalDateTime dispatchedAt);

    @Update("UPDATE fault SET status = 'CLOSED', confirmed_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'PENDING_CONFIRM'")
    int closePending(@Param("id") String id);

    /** 使用单位确认闭环并落安全管理员签字图，状态守卫与 closePending 一致。 */
    @Update("UPDATE fault SET status = 'CLOSED', confirmed_at = CURRENT_TIMESTAMP, signature = #{signature} "
            + "WHERE id = #{id} AND status = 'PENDING_CONFIRM'")
    int closePendingWithSignature(@Param("id") String id, @Param("signature") String signature);

    @Update("UPDATE fault SET status = 'REFUSED', refuse_reason = #{reason} "
            + "WHERE id = #{id} AND status = 'PENDING_CONFIRM'")
    int refusePending(@Param("id") String id, @Param("reason") String reason);

    /** 离职转交：锁定目标人员名下未闭环急修单，防止并发改派。 */
    @Select("SELECT * FROM fault WHERE dispatch_worker_id = #{employeeId} "
            + "AND status NOT IN ('CLOSED', 'CANCELLED') FOR UPDATE")
    List<Fault> selectUnclosedForUpdate(@Param("employeeId") String employeeId);

    /** 未开工/已派/待确认/已拒绝急修单改派给接手人（PROCESSING 无安全迁移规则，调用方须先拒绝）。 */
    @Update("UPDATE fault SET dispatch_worker_id = #{employeeId} "
            + "WHERE id = #{id} AND status NOT IN ('CLOSED', 'CANCELLED')")
    int reassignDispatchWorker(@Param("id") String id, @Param("employeeId") String employeeId);
}
