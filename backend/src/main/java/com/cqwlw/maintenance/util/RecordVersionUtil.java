package com.cqwlw.maintenance.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 返回 HashSet（contains(null) 安全）； */
public final class RecordVersionUtil {

    private RecordVersionUtil() {
    }

    /** 空/null/全 null id 入参返回空集且不发起查询——MyBatis-Plus in() 空集合会退化为无条件（全表扫描），必须短路； */
    public static Set<String> supersededIds(MaintainRecordMapper recordMapper, Collection<String> ids) {
        Set<String> out = new HashSet<>();
        if (recordMapper == null || ids == null || ids.isEmpty()) {
            return out;
        }
        Set<String> query = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null) {
                query.add(id);
            }
        }
        if (query.isEmpty()) {
            return out;
        }
        // 只需要父版本 ID，禁止把 items/photos/reportPayload 等 LONGTEXT 一并载入。
        List<MaintainRecord> rows = recordMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MaintainRecord>()
                .select("prev_version_id")
                .in("prev_version_id", query));
        for (MaintainRecord r : rows == null ? List.<MaintainRecord>of() : rows) {
            if (r != null && r.prevVersionId != null) {
                out.add(r.prevVersionId);
            }
        }
        return out;
    }
}
