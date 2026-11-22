package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.MaintainRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MaintainRecordMapper extends BaseMapper<MaintainRecord> {

    @Select("SELECT * FROM maintain_record WHERE id = #{id} FOR UPDATE")
    MaintainRecord selectByIdForUpdate(@Param("id") String id);

    @Update("UPDATE maintain_record parent LEFT JOIN maintain_record child ON child.prev_version_id = parent.id "
            + "SET parent.report_status = 'REUPLOADING' "
            + "WHERE parent.id = #{id} AND parent.report_status = 'FAILED' AND child.id IS NULL")
    int beginReupload(@Param("id") String id);

    @Update("UPDATE maintain_record SET report_status = #{reportStatus} "
            + "WHERE id = #{id} AND report_status = 'REUPLOADING'")
    int completeReupload(@Param("id") String id, @Param("reportStatus") String reportStatus);

    @Update("UPDATE maintain_record parent LEFT JOIN maintain_record child ON child.prev_version_id = parent.id "
            + "SET parent.report_status = 'LEGACY_UPLOADING' "
            + "WHERE parent.id = #{id} AND parent.report_status = 'SUBMITTED' AND child.id IS NULL")
    int beginLegacyUpload(@Param("id") String id);

    @Update("UPDATE maintain_record SET report_status = #{reportStatus} "
            + "WHERE id = #{id} AND report_status = 'LEGACY_UPLOADING'")
    int completeLegacyUpload(@Param("id") String id, @Param("reportStatus") String reportStatus);

    @Update("UPDATE maintain_record SET report_status = #{reportStatus}, retry_count = #{retryCount} "
            + "WHERE id = #{id}")
    int updateReportProgress(@Param("id") String id, @Param("reportStatus") String reportStatus,
                             @Param("retryCount") Integer retryCount);
}
