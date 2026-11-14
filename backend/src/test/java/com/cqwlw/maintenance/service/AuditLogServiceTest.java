package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.mapper.SysAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 审计 append-only：补 id/createdAt，无删改方法。 */
class AuditLogServiceTest {

    private final SysAuditLogMapper mapper = mock(SysAuditLogMapper.class);
    private final AuditLogService service = new AuditLogService(mapper);

    @Test
    void appendFillsIdAndCreatedAt() {
        SysAuditLog a = new SysAuditLog();
        a.action = "grant:create";
        a.result = "SUCCESS";
        service.append(a);
        ArgumentCaptor<SysAuditLog> cap = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(mapper).insert(cap.capture());
        assertNotNull(cap.getValue().id);
        assertNotNull(cap.getValue().createdAt);
    }

    @Test
    void noDeleteOrUpdateMethods() {
        for (Method m : AuditLogService.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            assertFalse(n.contains("delete") || n.contains("remove") || n.contains("update"),
                    "审计只读不可删改，发现写方法: " + m.getName());
        }
        assertTrue(java.util.Arrays.stream(AuditLogService.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("query")));
    }
}