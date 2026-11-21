package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysConfig;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.service.ClientSignSlaService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 签字 SLA（V56）：缺省 72h / 配置生效 / 非法值回退；起算=signRequestedAt、旧记录回退 createdAt */
class ClientSignSlaServiceTest {

    private static ClientSignSlaService service(String cfgValue) {
        SysConfigMapper configMapper = mock(SysConfigMapper.class);
        if (cfgValue != null) {
            SysConfig c = new SysConfig();
            c.cfgKey = ClientSignSlaService.CFG_KEY;
            c.cfgValue = cfgValue;
            when(configMapper.selectOne(any())).thenReturn(c);
        }
        return new ClientSignSlaService(configMapper);
    }

    @Test
    void defaultsTo72WhenMissingOrInvalid() {
        assertEquals(72, service(null).slaHours());
        assertEquals(72, service("abc").slaHours());
        assertEquals(72, service("0").slaHours());
        assertEquals(72, service("-5").slaHours());
        assertEquals(48, service("48").slaHours());
    }

    @Test
    void deadlineUsesSignRequestedAtThenCreatedAtFallback() {
        ClientSignSlaService svc = service("72");
        MaintainRecord r = new MaintainRecord();
        r.createdAt = LocalDateTime.of(2026, 11, 1, 9, 0);
        // 旧记录（无 signRequestedAt）回退 createdAt
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 0), svc.deadlineOf(r));
        assertEquals(r.createdAt, svc.baseOf(r));
        // 新记录以发起签字时刻起算
        r.signRequestedAt = LocalDateTime.of(2026, 11, 2, 10, 0);
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 0), svc.deadlineOf(r));
        assertEquals(r.signRequestedAt, svc.baseOf(r));
        // 起算缺失（异常数据）：无截止、不误报超期
        MaintainRecord empty = new MaintainRecord();
        assertNull(svc.deadlineOf(empty));
        assertFalse(svc.overdue(empty, LocalDateTime.of(2026, 12, 1, 0, 0)));
        assertTrue(svc.overdue(r, LocalDateTime.of(2026, 11, 5, 10, 1)));
    }
}
