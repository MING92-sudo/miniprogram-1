package com.cqwlw.maintenance.util;

import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 T5（N+1 批量化）：共享 supersededIds 与逐条 selectCount(prev_version_id=id) 语义等价
 * （docs/14 §4.6 规则 1：最新版本 = 不存在 prev_version_id = 本记录 id 的后续版本）。
 * isLatest(id) = !supersededIds(ids).contains(id)。
 */
class RecordVersionUtilTest {

    private static MaintainRecord rec(String id, String prevVersionId) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.prevVersionId = prevVersionId;
        return r;
    }

    /** 空/null 入参：返回空集且不发起查询（MyBatis-Plus in() 空集会退化为无条件=全表，必须短路） */
    @Test
    void emptyOrNullIdsReturnEmptyWithoutQuery() {
        MaintainRecordMapper mapper = mock(MaintainRecordMapper.class);
        assertTrue(RecordVersionUtil.supersededIds(mapper, List.of()).isEmpty());
        assertTrue(RecordVersionUtil.supersededIds(mapper, null).isEmpty());
        verify(mapper, never()).selectList(any());

        // 全 null id 同样短路不查（null 不进 IN 列表）
        MaintainRecordMapper m2 = mock(MaintainRecordMapper.class);
        assertTrue(RecordVersionUtil.supersededIds(m2, Arrays.asList((String) null)).isEmpty());
        verify(m2, never()).selectList(any());
    }

    /** 单条/多条 id：一次 IN 查询收集 prev_version_id 集（=被替代的记录 id）；无后续版本的 id 不在集内 */
    @Test
    void collectsSupersededIdsInSingleQuery() {
        MaintainRecordMapper mapper = mock(MaintainRecordMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                rec("ur_v2", "ur_1"), rec("ur_v3", "ur_2")));

        Set<String> out = RecordVersionUtil.supersededIds(mapper, List.of("ur_1", "ur_2", "ur_3"));

        assertEquals(Set.of("ur_1", "ur_2"), out);
        assertFalse(out.contains("ur_3")); // 链末端：不存在指向它的后续版本
        verify(mapper, times(1)).selectList(any());
    }

    /**
     * 结果行 prevVersionId=null 忽略；返回集 contains(null) 必须安全返回 false——
     * 不得用 Set.of()（其 contains(null) 抛 NPE，参见 2026-11-16 班组谓词 NULL 守卫 500 教训）
     */
    @Test
    void nullPrevVersionIdsIgnoredAndContainsNullSafe() {
        MaintainRecordMapper mapper = mock(MaintainRecordMapper.class);
        when(mapper.selectList(any())).thenReturn(Arrays.asList(rec("ur_x", null), null));

        Set<String> out = RecordVersionUtil.supersededIds(mapper, List.of("ur_1"));

        assertTrue(out.isEmpty());
        assertFalse(out.contains(null)); // 不抛 NPE
    }

    /** mapper 返回 null（防御）：空集不抛异常 */
    @Test
    void nullRowListIsSafe() {
        MaintainRecordMapper mapper = mock(MaintainRecordMapper.class);
        when(mapper.selectList(any())).thenReturn(null);
        assertTrue(RecordVersionUtil.supersededIds(mapper, List.of("ur_1")).isEmpty());
    }

    /** 等价性锁定：同一 fixture（版本链 v1←v2←v3 + 孤立记录）上，批量结果与逐条语义逐集合相等 */
    @Test
    void batchResultEqualsPerRecordSemantics() {
        // fixture：v2.prev=v1、v3.prev=v2（版本链）；solo 无链
        List<MaintainRecord> all = List.of(
                rec("v1", null), rec("v2", "v1"), rec("v3", "v2"), rec("solo", null));
        List<String> ids = all.stream().map(r -> r.id).toList();

        // 逐条语义（内存模拟旧实现 selectCount(prev_version_id=id)>0 → 被替代）
        Set<String> expected = new HashSet<>();
        for (String id : ids) {
            if (all.stream().anyMatch(next -> id.equals(next.prevVersionId))) {
                expected.add(id);
            }
        }

        MaintainRecordMapper mapper = mock(MaintainRecordMapper.class);
        // 模拟真实 IN 查询返回：prev_version_id ∈ ids 的后续版本行
        when(mapper.selectList(any())).thenReturn(all.stream()
                .filter(r -> r.prevVersionId != null && ids.contains(r.prevVersionId)).toList());

        assertEquals(expected, RecordVersionUtil.supersededIds(mapper, ids));
        assertEquals(Set.of("v1", "v2"), expected); // 链末端 v3 与 solo 不被替代
    }

    /** 含 null id 的混合入参：null 过滤后仍一次查询，非 null id 判定不受影响（null id 视为链末端） */
    @Test
    void nullIdsFilteredBeforeQuery() {
        MaintainRecordMapper mapper = mock(MaintainRecordMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(rec("ur_v2", "ur_1")));

        Set<String> out = RecordVersionUtil.supersededIds(mapper, Arrays.asList("ur_1", null));

        assertEquals(Set.of("ur_1"), out);
        assertFalse(out.contains(null)); // 逐条语义下 null id 记录 selectCount(=NULL)→0→最新版本，等价
        verify(mapper, times(1)).selectList(any());
    }
}
