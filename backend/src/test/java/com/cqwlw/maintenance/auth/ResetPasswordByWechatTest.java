package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.ClientLoginService;
import com.cqwlw.maintenance.service.WxAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 忘记密码：微信验证重置（code 服务端换 openid 匹配绑定账号；重置后清两端会话，未绑定/停用/弱密码拒写）。 */
class ResetPasswordByWechatTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final WxAuthService wxAuthService = mock(WxAuthService.class);
    private final ClientLoginService clientLoginService = mock(ClientLoginService.class);
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

    private AuthController controller() {
        return new AuthController(employeeMapper, mock(JwtService.class), wxAuthService,
                clientLoginService, mock(UserRoleService.class));
    }

    private Employee bound() {
        Employee e = new Employee();
        e.id = "emp_1";
        e.account = "13800000001";
        e.role = "WORKER";
        e.openid = "wx_openid_1";
        e.passwordHash = enc.encode("123456");
        e.enabled = true;
        e.sessionId = "sess_old";
        e.sessionAdmin = "sess_admin_old";
        return e;
    }

    /** 绑定账号 + 合法新密码：哈希更新、两端会话清空、回传账号供登录页预填 */
    @Test
    void resetUpdatesHashAndClearsSessions() {
        Employee e = bound();
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_openid_1");
        when(employeeMapper.selectOne(any())).thenReturn(e);

        Map<String, Object> data = controller().resetPasswordByWechat(
                Map.of("code", "code_ok", "newPassword", "NewPass123")).getData();

        assertEquals(true, data.get("ok"));
        assertEquals("13800000001", data.get("account"));
        assertTrue(enc.matches("NewPass123", e.passwordHash));
        verify(employeeMapper).updateById(e);
        verify(employeeMapper).clearSessions("emp_1");
    }

    /** 微信未绑定任何账号：422 引导管理员重置，不得落库 */
    @Test
    void unboundWechatRejectedWithoutWrite() {
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_unknown");
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientLoginService.activeAccountsByOpenid("wx_unknown")).thenReturn(java.util.List.of());

        BizException ex = assertThrows(BizException.class, () -> controller().resetPasswordByWechat(
                Map.of("code", "code_ok", "newPassword", "NewPass123")));

        assertEquals(422, ex.getCode());
        verify(employeeMapper, never()).updateById(any(Employee.class));
        verify(employeeMapper, never()).clearSessions(any());
    }

    /** 新密码 <6 位：422，不得落库 */
    @Test
    void shortPasswordRejectedWithoutWrite() {
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_openid_1");
        when(employeeMapper.selectOne(any())).thenReturn(bound());

        BizException ex = assertThrows(BizException.class, () -> controller().resetPasswordByWechat(
                Map.of("code", "code_ok", "newPassword", "123")));

        assertEquals(422, ex.getCode());
        verify(employeeMapper, never()).updateById(any(Employee.class));
        verify(employeeMapper, never()).clearSessions(any());
    }

    /** 停用账号：403，不得落库 */
    @Test
    void disabledAccountRejected() {
        Employee e = bound();
        e.enabled = false;
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_openid_1");
        when(employeeMapper.selectOne(any())).thenReturn(e);

        BizException ex = assertThrows(BizException.class, () -> controller().resetPasswordByWechat(
                Map.of("code", "code_ok", "newPassword", "NewPass123")));

        assertEquals(403, ex.getCode());
        verify(employeeMapper, never()).updateById(any(Employee.class));
    }

    /** 客户账号（client_account）绑定微信命中：重置客户口令，回传手机号 + CUSTOMER 主体 */
    @Test
    void resetsClientAccountPasswordByWechat() {
        when(wxAuthService.openidFromCode("code_c")).thenReturn("wx_client_1");
        when(employeeMapper.selectOne(any())).thenReturn(null);
        ClientAccount c = new ClientAccount();
        c.id = "ca_1";
        c.phone = "13800000003";
        c.status = ClientAccount.STATUS_ACTIVE;
        when(clientLoginService.activeAccountsByOpenid("wx_client_1")).thenReturn(java.util.List.of(c));

        Map<String, Object> data = controller().resetPasswordByWechat(
                Map.of("code", "code_c", "newPassword", "NewPass123")).getData();

        assertEquals(true, data.get("ok"));
        assertEquals("13800000003", data.get("account"));
        assertEquals("CUSTOMER", data.get("principalType"));
        verify(clientLoginService).resetPasswordByWechat("ca_1", "NewPass123");
    }

    /** code 无效：WxAuthService 422 透传，不得落库 */
    @Test
    void invalidCodePropagates422() {
        when(wxAuthService.openidFromCode("bad"))
                .thenThrow(new BizException(422, "微信登录凭证已失效，请重新进入小程序登录"));

        BizException ex = assertThrows(BizException.class, () -> controller().resetPasswordByWechat(
                Map.of("code", "bad", "newPassword", "NewPass123")));

        assertEquals(422, ex.getCode());
        verify(employeeMapper, never()).updateById(any(Employee.class));
    }
}