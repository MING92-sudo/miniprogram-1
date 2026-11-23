package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.IdempotencyKey;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface IdempotencyKeyMapper extends BaseMapper<IdempotencyKey> {

    @Update("UPDATE idempotency_key SET response_json = #{responseJson} "
            + "WHERE idem_key = #{key} AND owner_token = #{owner} AND response_json IS NULL")
    int complete(@Param("key") String key, @Param("owner") String owner,
                 @Param("responseJson") String responseJson);

    @Delete("DELETE FROM idempotency_key "
            + "WHERE idem_key = #{key} AND owner_token = #{owner} AND response_json IS NULL")
    int release(@Param("key") String key, @Param("owner") String owner);

    @Update("UPDATE idempotency_key SET heartbeat_at = #{heartbeatAt} "
            + "WHERE idem_key = #{key} AND owner_token = #{owner} AND response_json IS NULL")
    int heartbeat(@Param("key") String key, @Param("owner") String owner,
                  @Param("heartbeatAt") LocalDateTime heartbeatAt);

    @Delete("DELETE FROM idempotency_key WHERE idem_key = #{key} AND owner_token = #{owner} "
            + "AND response_json IS NULL AND heartbeat_at < #{cutoff}")
    int takeover(@Param("key") String key, @Param("owner") String owner,
                 @Param("cutoff") LocalDateTime cutoff);
}
