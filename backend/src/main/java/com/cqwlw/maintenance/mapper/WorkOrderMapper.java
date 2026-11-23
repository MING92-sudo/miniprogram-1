package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.WorkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

    /** 消费双人动态码的原子条件更新：仅当该工单尚未消费过动态码（NULL 或空串）时写入，命中 0 行即表示已被并发消费。 */
    @Update("UPDATE work_order SET dynamic_code = #{code}, dynamic_code_expires_at = #{expiresAt} "
            + "WHERE id = #{id} AND (dynamic_code IS NULL OR dynamic_code = '')")
    int consumeDynamicCodeIfUnused(@Param("id") String id,
                                   @Param("code") String code,
                                   @Param("expiresAt") LocalDateTime expiresAt);

    /** 区域抢单池认领：主维保首次签到时原子认领工单。 */
    @Update("UPDATE work_order SET worker_name = #{name}, worker_platform_id = #{pid} "
            + "WHERE id = #{id} AND (worker_platform_id IS NULL OR worker_platform_id = '') "
            + "AND status = 'PENDING'")
    int claimOrder(@Param("id") String id,
                   @Param("name") String name,
                   @Param("pid") String pid);

    /** 签退原子抢占：首次要求原记录 ID 为空，整改要求仍为本次读取到的原记录 ID。 */
    @Update("UPDATE work_order SET status = 'DONE', checkout_time = #{checkoutTime}, "
            + "original_record_id = #{originalRecordId}, report_status = #{reportStatus}, duration = #{duration} "
            + "WHERE id = #{id} AND status = 'PROCESSING' AND ("
            + "(#{expectedOriginalRecordId} IS NULL AND (original_record_id IS NULL OR original_record_id = '')) "
            + "OR (#{expectedOriginalRecordId} IS NOT NULL AND original_record_id = #{expectedOriginalRecordId}))")
    int completeCheckout(@Param("id") String id,
                         @Param("checkoutTime") LocalDateTime checkoutTime,
                         @Param("originalRecordId") String originalRecordId,
                         @Param("reportStatus") String reportStatus,
                         @Param("duration") String duration,
                         @Param("expectedOriginalRecordId") String expectedOriginalRecordId);

    @Update("UPDATE work_order SET assistant_signature_file_id = NULL, assistant_signature_at = NULL "
            + "WHERE id = #{id}")
    int clearAssistantSignature(@Param("id") String id);

    @Update("UPDATE work_order SET report_status = #{reportStatus} WHERE id = #{id}")
    int updateReportStatus(@Param("id") String id, @Param("reportStatus") String reportStatus);

    /** 离职转交：锁定目标人员作为主/配合人的全部未完成工单，防止并发改派。 */
    @Select("SELECT * FROM work_order WHERE (worker_platform_id = #{platformId} "
            + "OR assistant_platform_id = #{platformId}) AND status <> 'DONE' FOR UPDATE")
    List<WorkOrder> selectInflightForUpdate(@Param("platformId") String platformId);

    /** 主维保槽位改派：仅动主维保字段，保留配合人槽位与历史留痕。 */
    @Update("UPDATE work_order SET worker_name = #{name}, worker_platform_id = #{platformId} "
            + "WHERE id = #{id} AND status <> 'DONE' AND worker_platform_id = #{oldPlatformId}")
    int reassignWorker(@Param("id") String id, @Param("name") String name,
                       @Param("platformId") String platformId, @Param("oldPlatformId") String oldPlatformId);

    /** 配合人槽位改派：仅动配合人字段，保留主维保槽位与历史留痕。 */
    @Update("UPDATE work_order SET assistant_name = #{name}, assistant_platform_id = #{platformId} "
            + "WHERE id = #{id} AND status <> 'DONE' AND assistant_platform_id = #{oldPlatformId}")
    int reassignAssistant(@Param("id") String id, @Param("name") String name,
                          @Param("platformId") String platformId, @Param("oldPlatformId") String oldPlatformId);
}
