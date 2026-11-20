package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.ClientLoginService;
import com.cqwlw.maintenance.service.WxAuthService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 微信绑定/微信免密登录控制器接线：openid 只信 code 服务端换取；绑定先解绑他人再落库；未绑定 422、停用 403。 */
class WechatBindLoginTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final WxAuthService wxAuthService = mock(WxAuthService.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final UserRoleService userRoleService = mock(UserRoleService.class);
    private final ClientLoginService clientLoginService = mock(ClientLoginService.class);

    private AuthController controller() {
        return new AuthController(employeeMapper, jwtService, wxAuthService,
                clientLoginService, userRoleService);
    }

    private HttpServletRequest bearer() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer tok");
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("emp_1");
        when(jwtService.verify("tok")).thenReturn(claims);
        return req;
    }

    private Employee employee() {
        Employee e = new Employee();
        e.id = "emp_1";
        e.account = "13800000001";
        e.role = "WORKER";
        e.enabled = true;
        return e;
    }

    /** 绑定：code 换 openid → 先解绑其他持有者 → 写入当前账号（同事务） */
    @Test
    void bindWritesOpenidAndReleasesOtherHolders() {
        Employee e = employee();
        when(employeeMapper.selectById("emp_1")).thenReturn(e);
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_openid_9");
        when(employeeMapper.unbindOpenidExcept("wx_openid_9", "emp_1")).thenReturn(1);

        Map<String, Object> data = controller().bindWeChat(Map.of("code", "code_ok"), bearer()).getData();

        assertEquals(true, data.get("ok"));
        assertEquals("wx_openid_9", e.openid);
        verify(employeeMapper).unbindOpenidExcept("wx_openid_9", "emp_1");
        verify(employeeMapper).updateById(e);
    }

    /** 未登录绑定：401，不得换 openid/落库 */
    @Test
    void bindWithoutLoginRejected() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> controller().bindWeChat(Map.of("code", "code_ok"), req));

        assertEquals(401, ex.getCode());
        verify(wxAuthService, org.mockito.Mockito.never()).openidFromCode(anyString());
    }

    /** 微信免密登录：openid 命中绑定账号 → 签发会话（多角色扩展字段齐备） */
    @Test
    void wxLoginBoundAccountIssuesSession() {
        Employee e = employee();
        e.openid = "wx_openid_9";
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_openid_9");
        when(employeeMapper.selectOne(any())).thenReturn(e);
        when(userRoleService.canonicalRolesOf(any(), any())).thenReturn(java.util.Set.of("MAINT_TECH"));
        when(jwtService.issue(anyString(), anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn("mp-token");

        Map<String, Object> data = controller().wxLogin(Map.of("code", "code_ok")).getData();

        assertEquals("mp-token", data.get("token"));
        assertEquals("WORKER", data.get("role"));
        assertEquals("/pages/maint/home/index", data.get("home"));
        assertTrue(((java.util.List<?>) data.get("roles")).contains("MAINT_TECH"));
    }

    /** 微信未绑定：422（不能 401，避免前端误清登录态） */
    @Test
    void wxLoginUnboundReturns422() {
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_unknown");
        when(employeeMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> controller().wxLogin(Map.of("code", "code_ok")));

        assertEquals(422, ex.getCode());
    }

    /** 绑定账号被停用：403 */
    @Test
    void wxLoginDisabledAccountReturns403() {
        Employee e = employee();
        e.enabled = false;
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_openid_9");
        when(employeeMapper.selectOne(any())).thenReturn(e);

        BizException ex = assertThrows(BizException.class,
                () -> controller().wxLogin(Map.of("code", "code_ok")));

        assertEquals(403, ex.getCode());
    }
}