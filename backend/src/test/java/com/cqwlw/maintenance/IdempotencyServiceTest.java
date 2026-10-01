package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.IdempotencyKey;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写接口幂等：同 key 重放返回首次响应；并发同 key 拒绝；
 * 上一次异常终止（未写响应）的键**必须能过期回收**，否则该键将永久 422，
 * 离线队列任务（固定复用任务 id 作幂等键）将再也无法补传。
 */
class IdempotencyServiceTest {

    private IdempotencyService service;
    /** 模拟 idempotency_key 表 */
    private final Map<String, IdempotencyKey> table = new HashMap<>();

    @BeforeEach
    void setUp() {
        table.clear();
        IdempotencyKeyMapper mapper = Mockito.mock(IdempotencyKeyMapper.class);
        Mockito.when(mapper.selectById(Mockito.anyString()))
                .thenAnswer(inv -> copyOf(table.get(inv.<String>getArgument(0))));
        Mockito.when(mapper.insert(Mockito.any(IdempotencyKey.class))).thenAnswer(inv -> {
            IdempotencyKey row = inv.getArgument(0);
            table.put(row.idemKey, copyOf(row));
            return 1;
        });
        Mockito.when(mapper.updateById(Mockito.any(IdempotencyKey.class))).thenAnswer(inv -> {
            IdempotencyKey row = inv.getArgument(0);
            IdempotencyKey exist = table.get(row.idemKey);
            if (exist == null) {
                return 0;
            }
            exist.responseJson = row.responseJson;
            return 1;
        });
        Mockito.when(mapper.deleteById(Mockito.anyString())).thenAnswer(inv -> {
            table.remove(inv.<String>getArgument(0));
            return 1;
        });
        Mockito.when(mapper.delete(Mockito.any())).thenReturn(0);
        service = new IdempotencyService(mapper);
    }

    private static IdempotencyKey copyOf(IdempotencyKey src) {
        if (src == null) {
            return null;
        }
        IdempotencyKey c = new IdempotencyKey();
        c.idemKey = src.idemKey;
        c.path = src.path;
        c.responseJson = src.responseJson;
        c.createdAt = src.createdAt;
        return c;
    }

    @Test
    void replayReturnsFirstResponse() {
        IdempotencyService.Guard g = service.begin("k1", "/x");
        assertNotNull(g);
        assertEquals(false, g.replayed());
        g.commit(Map.of("ok", true));

        IdempotencyService.Guard again = service.begin("k1", "/x");
        assertTrue(again.replayed());
        assertEquals(Boolean.TRUE, again.replayedResult().get("ok"));
    }

    @Test
    void concurrentSameKeyRejected() {
        service.begin("k2", "/x");
        BizException e = assertThrows(BizException.class, () -> service.begin("k2", "/x"));
        assertEquals(422, e.getCode());
    }

    @Test
    void staleUncommittedKeyIsReclaimed() {
        // 回归：模拟上一次请求 begin() 后异常终止（从未 commit 响应）
        IdempotencyKey orphan = new IdempotencyKey();
        orphan.idemKey = "k3";
        orphan.path = "/work-orders/wo_1/checkout";
        orphan.responseJson = null;
        orphan.createdAt = TimeUtil.now().minusMinutes(11);
        table.put("k3", orphan);

        IdempotencyService.Guard g = service.begin("k3", "/work-orders/wo_1/checkout");
        assertEquals(false, g.replayed());
        // 回收后旧行已被清除，新一次占用写入新的 createdAt
        assertNotNull(table.get("k3"));
        assertTrue(table.get("k3").createdAt.isAfter(TimeUtil.now().minusMinutes(1)));
    }

    @Test
    void freshUncommittedKeyStillRejected() {
        // 未超时的"处理中"必须仍然拒绝，避免真正的并发重复提交被放行
        IdempotencyKey running = new IdempotencyKey();
        running.idemKey = "k4";
        running.responseJson = null;
        running.createdAt = TimeUtil.now().minusMinutes(2);
        table.put("k4", running);
        assertThrows(RuntimeException.class, () -> service.begin("k4", "/x"));
    }

    @Test
    void nullCreatedAtTreatedAsStale() {
        IdempotencyKey legacy = new IdempotencyKey();
        legacy.idemKey = "k5";
        legacy.createdAt = null;
        table.put("k5", legacy);
        assertEquals(false, service.begin("k5", "/x").replayed());
    }

    @Test
    void completedResponseNeverExpires() {
        // 已完成的记录即便很老也不能被当作"处理中"回收，否则会把真实重放变成重复执行
        IdempotencyKey done = new IdempotencyKey();
        done.idemKey = "k6";
        done.responseJson = "{\"ok\":true}";
        done.createdAt = TimeUtil.now().minusDays(3);
        table.put("k6", done);
        assertTrue(service.begin("k6", "/x").replayed());
    }
}