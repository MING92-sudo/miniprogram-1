package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.SysGrant;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.SysGrantMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 限时授权：end>start、四态生命周期、回收递增 scope_version+审计、解析并集。 */
class GrantServiceTest {

    private final SysGrantMapper grantMapper = mock(SysGrantMapper.class);
    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final GrantService service = new GrantService(grantMapper, employeeMapper, audit);

    @Test
    void endMustBeAfterStart() {
        LocalDateTime now = TimeUtil.now();
        assertThrows(BizException.class, () -> service.create("emp_1", "order:dispatch", null, null,
                now, now.minusDays(1), "emp_mgr", "支援"));
    }

    @Test
    void futureGrantIsPendingPastGrantIsActive() {
        LocalDateTime now = TimeUtil.now();
        SysGrant future = service.create("emp_1", "order:dispatch", null, null,
                now.plusDays(1), now.plusDays(2), "emp_mgr", "支援");
        assertEquals("PENDING", future.status);
        SysGrant active = service.create("emp_1", "order:dispatch", null, null,
                now.minusDays(1), now.plusDays(1), "emp_mgr", "支援");
        assertEquals("ACTIVE", active.status);
        verify(grantMapper, atLeastOnce()).insert(any(SysGrant.class));
    }

    @Test
    void revokeExpiredSetsRevokedAndBumpsScopeVersion() {
        LocalDateTime now = TimeUtil.now();
        SysGrant expired = new SysGrant();
        expired.id = "g1"; expired.granteeId = "emp_1"; expired.status = "ACTIVE";
        expired.endAt = now.minusMinutes(1);
        SysGrant pending = new SysGrant();
        pending.id = "g2"; pending.granteeId = "emp_2"; pending.status = "PENDING";
        pending.startAt = now.minusMinutes(1); pending.endAt = now.plusDays(1);
        when(grantMapper.selectList(any())).thenReturn(List.of(expired, pending));

        int n = service.revokeExpired();
        assertEquals(2, n);
        assertEquals("REVOKED", expired.status);
        assertEquals("ACTIVE", pending.status);
        verify(employeeMapper).bumpScopeVersion("emp_1"); // 回收递增被授权人 scope_version
        verify(audit, atLeastOnce()).append(any());
    }

    @Test
    void activePermCodesUnion() {
        SysGrant g = new SysGrant();
        g.granteeId = "emp_1"; g.permCode = "order:dispatch"; g.status = "ACTIVE";
        when(grantMapper.selectList(any())).thenReturn(List.of(g));
        assertTrue(service.activePermCodes("emp_1").contains("order:dispatch"));
    }
}