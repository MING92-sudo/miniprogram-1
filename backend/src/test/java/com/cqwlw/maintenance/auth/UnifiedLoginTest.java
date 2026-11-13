package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.UnitAccountService;
import com.cqwlw.maintenance.service.WxAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 统一登录页（用户需求）：只保留一个登录界面，后端按账号类型分流——
 * 员工账号走原 JWT 会话；使用单位账号返回 portal=true，前端据此跳门户首页。
 */
class UnifiedLoginTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final UnitAccountService unitAccountService = mock(UnitAccountService.class);
    private final JwtService jwtService = mock(JwtService.class);

    private AuthController controller() {
        return new AuthController(employeeMapper, jwtService, mock(WxAuthService.class),
                mock(MenuService.class), unitAccountService);
    }

    /** 员工目录查不到 → 自动按使用单位账号再试，返回 portal=true */
    @Test
    void fallsBackToUnitAccount() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(unitAccountService.login("U0000001", "Passw0rd!23")).thenReturn(Map.of(
                "token", "unit-token", "useUnitId", "uu_1", "unitName", "重庆渝达物业",
                "role", "UNIT_ADMIN", "wechatBound", false));

        Map<String, Object> data = controller().login(Map.of("phone", "U0000001", "password", "Passw0rd!23")).getData();
        assertEquals(true, data.get("portal"));
        assertEquals("UNIT_ADMIN", data.get("role"));
        assertEquals("uu_1", data.get("useUnitId"));
        assertTrue(String.valueOf(data.get("userInfo")).contains("重庆渝达物业"));
    }

    /** 员工账号仍走原流程：portal 不出现（不能把员工当门户账号） */
    @Test
    void employeeLoginUnchanged() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        Employee e = new Employee();
        e.id = "emp_1";
        e.name = "张伟";
        e.role = "WORKER";
        e.account = "13800000001";
        e.passwordHash = enc.encode("Passw0rd!23");
        e.enabled = true;
        when(employeeMapper.selectOne(any())).thenReturn(e);
        when(jwtService.issue(anyString(), anyString(), any(), anyString(), anyString())).thenReturn("emp-token");

        Map<String, Object> data = controller().login(Map.of("phone", "13800000001", "password", "Passw0rd!23")).getData();
        assertEquals("WORKER", data.get("role"));
        assertEquals("emp-token", data.get("token"));
        assertEquals(null, data.get("portal"));
    }

    /** 管理端登录不接受使用单位账号（client=admin） */
    @Test
    void unitAccountCannotUseAdminConsole() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> controller().login(
                Map.of("phone", "U0000001", "password", "x", "client", "admin")));
        assertEquals(403, ex.getCode());
    }
}
