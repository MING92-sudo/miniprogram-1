package com.cqwlw.maintenance.auth;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.ClientLoginService;
import com.cqwlw.maintenance.service.WxAuthService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 登出吊销回归（E2E 发现）：`POST /auth/logout` 原为空实现，返回 {ok:true} 却不做任何事，
 * 令牌在有效期内仍可继续调用受保护接口。修复后必须清会话并递增 scope_version。
 */
class AuthControllerLogoutTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final JwtService jwtService = mock(JwtService.class);

    @BeforeEach
    void initTableInfo() {
        // LambdaUpdateWrapper 需要 TableInfo 缓存（纯 Mockito 单测无 MyBatis 环境，手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Employee.class);
    }

    private AuthController controller(ClientLoginService clientLogin) {
        return new AuthController(employeeMapper, jwtService, mock(WxAuthService.class),
                clientLogin, mock(UserRoleService.class));
    }

    private HttpServletRequest bearer(String token) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(token == null ? null : "Bearer " + token);
        return req;
    }

    @Test
    void logoutRevokesEmployeeSession() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("emp_1");
        when(claims.get("ut", String.class)).thenReturn(null);
        when(jwtService.verify("tok")).thenReturn(claims);

        Employee e = new Employee();
        e.id = "emp_1";
        e.scopeVersion = 3;
        when(employeeMapper.selectById("emp_1")).thenReturn(e);

        controller(mock(ClientLoginService.class)).logout(bearer("tok"));

        // 必须显式更新（sid 置空 + sv 递增），否则旧令牌继续可用
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Employee>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(employeeMapper).update(isNull(), captor.capture());

        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("session_id"), "吊销必须置空 session_id，实际：" + sqlSet);
        assertTrue(sqlSet.contains("scope_version"), "吊销必须递增 scope_version，实际：" + sqlSet);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(4),
                "scope_version 应递增为 4，实际参数：" + captor.getValue().getParamNameValuePairs());
    }

    @Test
    void logoutWithCustomerTokenRevokesClientAccountSession() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("ca_1");
        when(claims.get("ut", String.class)).thenReturn("2");
        when(jwtService.verify("tok")).thenReturn(claims);
        ClientLoginService clientLogin = mock(ClientLoginService.class);

        controller(clientLogin).logout(bearer("tok"));

        verify(clientLogin).revokeSession("ca_1");
        verifyNoInteractions(employeeMapper);
    }

    @Test
    void logoutWithoutTokenIsNoop() {
        controller(mock(ClientLoginService.class)).logout(bearer(null));

        verifyNoInteractions(employeeMapper, jwtService);
    }

    @Test
    void invalidTokenStillReturnsOkWithoutTouchingDatabase() {
        when(jwtService.verify("tok")).thenThrow(new IllegalArgumentException("expired"));

        var resp = controller(mock(ClientLoginService.class)).logout(bearer("tok"));

        assertTrue(resp.getData().get("ok").equals(Boolean.TRUE));
        verifyNoInteractions(employeeMapper);
    }

    /**
     * 回归：吊销失败不能被吞掉。修复前 try 块包住了 update，写库异常会被静默忽略，
     * 接口返回 ok 而会话仍在——必须向外抛错，让调用方知道登出未生效。
     */
    @Test
    void revokeFailurePropagatesInsteadOfSilentlySucceeding() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("emp_1");
        when(claims.get("ut", String.class)).thenReturn(null);
        when(jwtService.verify("tok")).thenReturn(claims);

        Employee e = new Employee();
        e.id = "emp_1";
        e.scopeVersion = 0;
        when(employeeMapper.selectById("emp_1")).thenReturn(e);
        when(employeeMapper.update(isNull(), any())).thenThrow(new RuntimeException("db down"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> controller(mock(ClientLoginService.class)).logout(bearer("tok")));
        assertTrue(ex.getMessage().contains("db down"));
    }
}
