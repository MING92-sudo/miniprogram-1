package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.service.ClientMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 标读校验归属，越权 1403 无副作用 */
class ClientMessageServiceTest {

    private MessageMapper messageMapper;
    private ClientMessageService service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(MessageMapper.class);
        service = new ClientMessageService(messageMapper);
    }

    private Message msg(String id, String useUnitId, boolean read) {
        Message m = new Message();
        m.id = id;
        m.title = "签字临期提醒";
        m.content = "内容";
        m.useUnitId = useUnitId;
        m.readFlag = read;
        return m;
    }

    @Test
    void listAndUnreadCountAreScoped() {
        when(messageMapper.selectList(any())).thenReturn(List.of(msg("m_1", "uu_1", false)));
        List<Map<String, Object>> list = service.list("uu_1");
        assertEquals(1, list.size());
        assertEquals("m_1", list.get(0).get("id"));
        assertEquals(Boolean.FALSE, list.get(0).get("readFlag"));
        assertTrue(service.list(null).isEmpty());
        assertTrue(service.list("").isEmpty());
        when(messageMapper.selectCount(any())).thenReturn(2L);
        assertEquals(2L, service.unreadCount("uu_1").get("count"));
        assertEquals(0L, service.unreadCount(null).get("count"));
    }

    @Test
    void markReadChecksOwnership() {
        Message own = msg("m_1", "uu_1", false);
        when(messageMapper.selectById("m_1")).thenReturn(own);
        assertEquals(Boolean.TRUE, service.markRead("uu_1", "m_1").get("ok"));
        assertEquals(Boolean.TRUE, own.readFlag);

        Message foreign = msg("m_2", "uu_2", false);
        when(messageMapper.selectById("m_2")).thenReturn(foreign);
        assertThrows(BizException.class, () -> service.markRead("uu_1", "m_2"));
        assertEquals(Boolean.FALSE, foreign.readFlag); // 越权无副作用
        verify(messageMapper, never()).updateById(foreign);
    }

    @Test
    void markReadMissingIs1404() {
        when(messageMapper.selectById("m_x")).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.markRead("uu_1", "m_x"));
        assertEquals(1404, e.getCode());
    }
}
