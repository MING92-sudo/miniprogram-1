package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.ClientAccountMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClientAccountServiceTest {

    private ClientAccountMapper mapper;
    private UseUnitMapper useUnitMapper;
    private EmployeeMapper employeeMapper;
    private ClientAccountService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ClientAccountMapper.class);
        useUnitMapper = mock(UseUnitMapper.class);
        employeeMapper = mock(EmployeeMapper.class);
        service = new ClientAccountService(mapper, useUnitMapper, employeeMapper, mock(AuditLogService.class));
        UseUnit unit = new UseUnit();
        unit.id = "uu_1";
        unit.unitName = "测试物业";
        when(useUnitMapper.selectById("uu_1")).thenReturn(unit);
        when(useUnitMapper.selectById("uu_missing")).thenReturn(null);
    }

    private Map<String, Object> body(String name, String phone, String role) {
        return Map.of("name", name, "phone", phone, "role", role);
    }

    @Test
    void createRequiresExistingUnit() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create("uu_missing", body("张三", "13900001111", "CLIENT_ADMIN"), "op1"));
        assertEquals(1404, ex.getCode());
    }

    @Test
    void createRejectsInvalidPhone() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create("uu_1", body("张三", "123", "CLIENT_ADMIN"), "op1"));
        assertEquals(422, ex.getCode());
    }

    @Test
    void createRejectsUnknownRole() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create("uu_1", body("张三", "13900001111", "WORKER"), "op1"));
        assertEquals(422, ex.getCode());
    }

    @Test
    void createRejectsDuplicateActivePhoneInSameUnit() {
        ClientAccount dup = account("ca_dup", "13900001111", ClientAccount.STATUS_ACTIVE);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dup);
        BizException ex = assertThrows(BizException.class,
                () -> service.create("uu_1", body("李四", "13900001111", "CLIENT_SIGNER"), "op1"));
        assertEquals(1002, ex.getCode());
    }

    @Test
    void createRejectsEmployeePhoneConflict() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        Employee emp = new Employee();
        emp.phone = "13900001111";
        when(employeeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(emp);
        BizException ex = assertThrows(BizException.class,
                () -> service.create("uu_1", body("李四", "13900001111", "CLIENT_SIGNER"), "op1"));
        assertEquals(1002, ex.getCode());
    }

    @Test
    void createDefaultsToAdminRoleAndEchoesInitialPassword() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(employeeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        Map<String, Object> out = service.create("uu_1",
                Map.of("name", "张三", "phone", "13900001111"), "op1");
        assertEquals("CLIENT_ADMIN", out.get("role"));
        assertEquals("CUSTOMER", out.get("principalType"));
        assertNotNull(out.get("initialPassword")); // 初始口令仅回显一次
        verify(mapper).insert(any(ClientAccount.class));
    }

    @Test
    void disableClearsSessionAndBumpsScopeVersion() {
        ClientAccount c = account("ca_1", "13900001111", ClientAccount.STATUS_ACTIVE);
        c.sessionId = "sess_old";
        c.scopeVersion = 3;
        when(mapper.selectById("ca_1")).thenReturn(c);
        Map<String, Object> out = service.disable("ca_1", "op1", "换人");
        assertEquals("DISABLED", out.get("status"));
        assertNull(c.sessionId);
        assertEquals(4, c.scopeVersion);
        assertTrue(c.disabledReason.startsWith("MANUAL_"));
        verify(mapper).updateById(c);
    }

    @Test
    void enableRejectsPhoneConflictWithAnotherActiveAccount() {
        ClientAccount c = account("ca_1", "13900001111", ClientAccount.STATUS_DISABLED);
        when(mapper.selectById("ca_1")).thenReturn(c);
        ClientAccount other = account("ca_2", "13900001111", ClientAccount.STATUS_ACTIVE);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(other);
        BizException ex = assertThrows(BizException.class, () -> service.enable("ca_1", "op1"));
        assertEquals(1002, ex.getCode());
    }

    @Test
    void resetPasswordClearsSessionAndEchoesPassword() {
        ClientAccount c = account("ca_1", "13900001111", ClientAccount.STATUS_ACTIVE);
        c.sessionId = "sess_old";
        when(mapper.selectById("ca_1")).thenReturn(c);
        Map<String, Object> out = service.resetPassword("ca_1", Map.of("password", "newpass123"), "op1");
        assertEquals("newpass123", out.get("password"));
        assertNull(c.sessionId);
        verify(mapper).updateById(c);
    }

    @Test
    void rowNeverLeaksPasswordHashOrSession() {
        ClientAccount c = account("ca_1", "13900001111", ClientAccount.STATUS_ACTIVE);
        c.passwordHash = "bcrypt$secrethash";
        c.sessionId = "sess_secret";
        c.openid = "openid_secret";
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(c));
        Map<String, Object> row = service.listByUnit("uu_1").get(0);
        assertFalse(row.containsKey("passwordHash"));
        assertFalse(row.containsKey("sessionId"));
        assertFalse(row.containsKey("openid"));
        assertFalse(String.valueOf(row).contains("secrethash"));
    }


    @Test
    void sweepDisablesExpiredGraceAccountAndKillsSession() {
        ClientAccount c = account("ca_grace", "13900002222", ClientAccount.STATUS_GRACE);
        c.graceUntil = LocalDateTime.now().minusDays(1);
        c.scopeVersion = 3;
        c.sessionId = "sess_alive";
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(c));

        int affected = service.sweepExpiredGrace();

        assertEquals(1, affected);
        assertEquals(ClientAccount.STATUS_DISABLED, c.status);
        assertEquals("GRACE_EXPIRED", c.disabledReason);
        assertEquals(0, c.enabled);
        assertNull(c.sessionId, "到期必须清会话");
        assertEquals(4, c.scopeVersion, "递增 scope_version 使旧 token 失效");
        assertNotNull(c.disabledAt);
        verify(mapper).updateById(c);
    }

    @Test
    void sweepIsNoOpWhenNothingExpired() {
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of());
        assertEquals(0, service.sweepExpiredGrace());
        verify(mapper, never()).updateById(any(ClientAccount.class));
    }

    private static ClientAccount account(String id, String phone, String status) {
        ClientAccount c = new ClientAccount();
        c.id = id;
        c.useUnitId = "uu_1";
        c.name = "测试客户";
        c.phone = phone;
        c.roleCode = ClientAccount.ROLE_ADMIN;
        c.status = status;
        c.enabled = ClientAccount.STATUS_DISABLED.equals(status) ? 0 : 1;
        c.scopeVersion = 1;
        c.createdAt = LocalDateTime.now();
        return c;
    }
}
