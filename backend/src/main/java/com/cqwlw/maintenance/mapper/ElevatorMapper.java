package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.Elevator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface ElevatorMapper extends BaseMapper<Elevator> {

    @Update("UPDATE elevator SET lng = #{lng}, lat = #{lat}, geo_status = 'SELF_COLLECTED' "
            + "WHERE id = #{id} AND (lng IS NULL OR lat IS NULL)")
    int backfillGeoIfMissing(@Param("id") String id, @Param("lng") BigDecimal lng, @Param("lat") BigDecimal lat);

    @Select("SELECT * FROM elevator WHERE id = #{id} FOR UPDATE")
    Elevator selectByIdForUpdate(@Param("id") String id);
}
