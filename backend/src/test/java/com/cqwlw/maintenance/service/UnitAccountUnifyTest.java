package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ClientAccountMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用单位门户账号迁移（V34 → P3）：目标表为 client_account，绝不再写 sys_employee。
 * 覆盖：开通客户账号、手机号被员工占用跳过、幂等（已有账号只补标记）、
 * 口令沿用/兜底、openid 不抢夺、空列表 no-op。
 */
class UnitAccountUnifyTest {

    private UseUnitMapper units;
    private EmployeeMapper emps;
    private ClientAccountMapper clients;
    private UnitAccountUnifyService svc;

    private void setUp(List<UseUnit> pending, Employee employeeHolder, ClientAccount existing, Long openidHeld) {
        units = mock(UseUnitMapper.class);
        emps = mock(EmployeeMapper.class);
        clients = mock(ClientAccountMapper.class);
        when(units.selectList(any())).thenReturn(pending);
        when(emps.selectOne(any())).thenReturn(employeeHolder);
        when(clients.selectOne(any())).thenReturn(existing);
        when(clients.selectCount(any())).thenReturn(openidHeld == null ? 0L : openidHeld);
        svc = new UnitAccountUnifyService(units, emps, clients);
    }

    private UseUnit unit() {
        UseUnit u = new UseUnit();
        u.id = "u1";
        u.unitName = "启元物业";
        u.account = "13800000000";
        u.passwordHash = "hash-unit";
        u.accountMigrated = 0;
        return u;
    }

    @Test
    void portalAccountCreatesClientAccountInsteadOfEmployee() {
        setUp(List.of(unit()), null, null, null);
        assertEquals(1, svc.unifyPending());
        verify(emps, never()).insert(any(Employee.class));
        ArgumentCaptor<ClientAccount> cap = ArgumentCaptor.forClass(ClientAccount.class);
        verify(clients).insert(cap.capture());
        ClientAccount c = cap.getValue();
        assertEquals("u1", c.useUnitId);
        assertEquals("13800000000", c.phone);
        assertEquals(ClientAccount.ROLE_ADMIN, c.roleCode);
        assertEquals(ClientAccount.STATUS_ACTIVE, c.status);
        assertEquals(1, c.enabled);
        assertEquals("hash-unit", c.passwordHash, "门户口令哈希应沿用，避免要求客户改密");
    }

    @Test
    void phoneOwnedByInternalEmployeeIsSkipped() {
        Employee maint = new Employee();
        maint.id = "e1";
        maint.phone = "13800000000";
        UseUnit u = unit();
        setUp(List.of(u), maint, null, null);
        assertEquals(0, svc.unifyPending());
        verify(clients, never()).insert(any(ClientAccount.class));
        assertEquals(0, u.accountMigrated, "跨主体冲突时保持未迁移，待人工处理");
    }

    @Test
    void existingClientAccountIsIdempotent() {
        ClientAccount exist = new ClientAccount();
        exist.id = "ca_old";
        exist.phone = "13800000000";
        UseUnit u = unit();
        setUp(List.of(u), null, exist, null);
        assertEquals(1, svc.unifyPending());
        verify(clients, never()).insert(any(ClientAccount.class));
        assertEquals(1, u.accountMigrated);
        verify(units).updateById(u);
    }

    @Test
    void missingPasswordFallsBackToPhoneLastSix() {
        UseUnit u = unit();
        u.passwordHash = null;
        setUp(List.of(u), null, null, null);
        svc.unifyPending();
        ArgumentCaptor<ClientAccount> cap = ArgumentCaptor.forClass(ClientAccount.class);
        verify(clients).insert(cap.capture());
        assertTrue(new BCryptPasswordEncoder().matches("000000", cap.getValue().passwordHash),
                "缺失门户口令时按手机号后 6 位初始化（与客户账号开通口径一致）");
    }

    @Test
    void freeOpenidIsCopied() {
        UseUnit u = unit();
        u.openid = "o-free";
        setUp(List.of(u), null, null, null);
        svc.unifyPending();
        ArgumentCaptor<ClientAccount> cap = ArgumentCaptor.forClass(ClientAccount.class);
        verify(clients).insert(cap.capture());
        assertEquals("o-free", cap.getValue().openid);
    }

    @Test
    void occupiedOpenidIsNotStolen() {
        UseUnit u = unit();
        u.openid = "o-shared";
        setUp(List.of(u), null, null, 1L);
        svc.unifyPending();
        ArgumentCaptor<ClientAccount> cap = ArgumentCaptor.forClass(ClientAccount.class);
        verify(clients).insert(cap.capture());
        assertNull(cap.getValue().openid, "同单位其他客户账号已绑定该微信，不抢夺");
    }

    @Test
    void emptyPendingIsNoOp() {
        setUp(List.of(), null, null, null);
        assertEquals(0, svc.unifyPending());
        verify(clients, never()).insert(any(ClientAccount.class));
        verify(units, never()).updateById(any(UseUnit.class));
    }
}
