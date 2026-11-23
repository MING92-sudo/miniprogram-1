package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.IdempotencyKey;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 无响应的悬挂幂等记录（业务异常后未 commit）10 分钟过期放行——否则离线队列同 key 重试永远 422，任务永久卡死。 */
class IdempotencyServiceTest {

    private final IdempotencyKeyMapper mapper = mock(IdempotencyKeyMapper.class);
    private final IdempotencyService service = new IdempotencyService(mapper);

    private IdempotencyKey row(String responseJson, java.time.LocalDateTime createdAt) {
        IdempotencyKey k = new IdempotencyKey();
        k.idemKey = "k1";
        k.responseJson = responseJson;
        k.createdAt = createdAt;
        return k;
    }

    @Test
    void staleEmptyRecordIsReleasedAfter10Minutes() {
        when(mapper.selectById("k1"))
                .thenReturn(row(null, TimeUtil.now().minusMinutes(11)));
        assertDoesNotThrow(() -> service.begin("k1", "/work-orders/x"));
        verify(mapper).deleteById("k1");
    }

    @Test
    void freshEmptyRecordStillRejected() {
        when(mapper.selectById("k1")).thenReturn(row(null, TimeUtil.now()));
        assertThrows(BizException.class, () -> service.begin("k1", "/work-orders/x"));
    }

    @Test
    void completedRecordReplays() {
        when(mapper.selectById("k1")).thenReturn(row("{\"code\":0}", TimeUtil.now()));
        assertTrue(service.begin("k1", "/work-orders/x").replayed());
    }

    @Test
    void commitRedactsSecretsWithoutMutatingOriginalResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "emp_1");
        result.put("initialPassword", "root-secret");
        result.put("password", "plain-secret");

        IdempotencyKey persisted = persist("secret-root", result);

        Map<String, Object> stored = JsonUtil.readMap(persisted.responseJson);
        assertEquals("emp_1", stored.get("id"));
        assertFalse(stored.containsKey("initialPassword"));
        assertFalse(stored.containsKey("password"));
        assertEquals("root-secret", result.get("initialPassword"));
        assertEquals("plain-secret", result.get("password"));
    }

    @Test
    void commitRedactsNestedSecretsInObjectsAndArrays() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("label", "保留");
        nested.put("password", "nested-secret");
        Map<String, Object> arrayItem = new LinkedHashMap<>();
        arrayItem.put("name", "保留项");
        arrayItem.put("initialPassword", "array-secret");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nested", nested);
        result.put("items", List.of(arrayItem));

        Map<String, Object> stored = JsonUtil.readMap(persist("secret-nested", result).responseJson);

        Map<?, ?> storedNested = (Map<?, ?>) stored.get("nested");
        assertEquals("保留", storedNested.get("label"));
        assertFalse(storedNested.containsKey("password"));
        Map<?, ?> storedItem = (Map<?, ?>) ((List<?>) stored.get("items")).get(0);
        assertEquals("保留项", storedItem.get("name"));
        assertFalse(storedItem.containsKey("initialPassword"));
    }

    @Test
    void replayedResultContainsNoPersistedSecrets() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "ca_1");
        result.put("initialPassword", "replay-secret");
        IdempotencyKey persisted = persist("secret-replay", result);
        when(mapper.selectById("secret-replay")).thenReturn(persisted);

        IdempotencyService.Guard replay = service.begin("secret-replay", "/use-units/u1/accounts");

        assertTrue(replay.replayed());
        Map<String, Object> replayed = replay.replayedResult();
        assertEquals("ca_1", replayed.get("id"));
        assertFalse(replayed.containsKey("initialPassword"));
    }

    private IdempotencyKey persist(String key, Map<String, Object> result) {
        IdempotencyService.Guard guard = service.begin(key, "/test");
        guard.commit(result);
        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(mapper).updateById(captor.capture());
        return captor.getValue();
    }
}
