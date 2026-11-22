package com.cqwlw.maintenance.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 维保记录版本链「最新版本」批量判定（P2 T5 消除 N+1，docs/14 §4.6 规则 1）：
 * 最新版本 = 不存在 prev_version_id = 本记录 id 的后续版本。
 * UnitRecordService.pendingAs / UnitPortalService.visibleRecords /
 * MiniappMenuService.clientPendingSigns 三处列表链路共享：一次 IN 查询收集
 * 「已被替代的记录 id」集，isLatest(id) = !supersededIds(...).contains(id)，
 * 与逐条 selectCount(prev_version_id = id) == 0 语义逐集合相等。
 * 返回 HashSet（contains(null) 安全）；不得改用 Set.of()——其 contains(null)
 * 抛 NPE（2026-11-16 班组谓词 NULL 守卫 500 代 1403 教训）。
 */
public final class RecordVersionUtil {

    private RecordVersionUtil() {
    }

    /**
     * 批量取「已被后续版本替代」的记录 id 集。
     * 空/null/全 null id 入参返回空集且不发起查询——MyBatis-Plus in() 空集合
     * 会退化为无条件（全表扫描），必须短路；null id 过滤后不入 IN 列表
     * （等价于逐条 selectCount(prev_version_id = NULL) 恒 0，null id 视为链末端）。
     *
     * @param recordMapper 维保记录 Mapper（调用方注入，本类不持 Spring 依赖、无循环依赖）
     * @param ids          候选记录 id 集（容忍 null 元素）
     * @return 被替代记录 id 集（HashSet，contains(null) 安全返回 false）
     */
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
