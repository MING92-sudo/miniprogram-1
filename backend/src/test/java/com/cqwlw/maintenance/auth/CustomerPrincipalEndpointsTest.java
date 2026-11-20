package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.mapper.ClientAccountMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.ClientLoginService;
import com.cqwlw.maintenance.service.WxAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 客户主体在「员工端点」上的行为回归（小程序端实测发现）：
 * /me/menus 与 /auth/me 都在客户端白名单内，若对客户返回 401，前端 request 层会清登录态并回登录页，
 * 表现为「客户登录成功后立刻被踢回登录页」——两个端点必须对客户返回 200。
 */
class CustomerPrincipalEndpointsTest {

    private HttpServletRequest customerRequest(String accountId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_PRINCIPAL_TYPE)).thenReturn("CUSTOMER");
        when(req.getAttribute(AuthInterceptor.ATTR_EMP_ID)).thenReturn(accountId);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("CLIENT_ADMIN");
        return req;
    }

    private ClientAccount account() {
        ClientAccount a = new ClientAccount();
        a.id = "ca_1";
        a.name = "王芳";
        a.phone = "13800000003";
        a.useUnitId = "uu_1";
        a.status = ClientAccount.STATUS_ACTIVE;
        return a;
    }

    @Test
    void meMenusReturnsCustomerMenuInsteadOf401() {
        MiniappMenuService menuService = mock(MiniappMenuService.class);
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        MiniappMenuController controller =
                new MiniappMenuController(menuService, employeeMapper, mock(JwtService.class));

        var resp = controller.meMenus(customerRequest("ca_1"), null);

        assertEquals(0, resp.getCode());
        assertEquals("CUSTOMER", resp.getData().get("principalType"));
        assertEquals("/pages/client/home/index", resp.getData().get("home"));
        // 客户不在员工目录里，不允许拿客户 id 去查员工表
        verifyNoInteractions(employeeMapper, menuService);
    }

    @Test
    void meReturnsCustomerProfileInsteadOf401() {
        ClientAccountMapper clientAccountMapper = mock(ClientAccountMapper.class);
        when(clientAccountMapper.selectById("ca_1")).thenReturn(account());
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        AuthController controller = new AuthController(employeeMapper, mock(JwtService.class),
                mock(WxAuthService.class), mock(ClientLoginService.class),
                mock(UserRoleService.class));
        controller.setClientAccountMapper(clientAccountMapper);

        var resp = controller.me(customerRequest("ca_1"));

        assertEquals(0, resp.getCode());
        assertEquals("CUSTOMER", resp.getData().get("principalType"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> info = (java.util.Map<String, Object>) resp.getData().get("userInfo");
        assertEquals("王芳", info.get("name"));
        assertEquals("uu_1", info.get("useUnitId"));
        verifyNoInteractions(employeeMapper);
    }

    @Test
    void employeeWithoutPrincipalTypeStillGets401WhenMissing() {
        // 非客户主体（缺 attribute）且查不到员工 → 仍应 401，不能被客户分支误放行
        MiniappMenuController controller = new MiniappMenuController(
                mock(MiniappMenuService.class), mock(EmployeeMapper.class), mock(JwtService.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(null);

        var ex = assertThrows(com.cqwlw.maintenance.common.BizException.class,
                () -> controller.meMenus(req, null));
        assertEquals(401, ex.getCode());
        assertFalse(ex.getMessage().isEmpty());
    }

    // ── 2026-11-20 静态排查：白名单放行但只认员工主体的三个端点 ──────────────

    @Test
    void changePasswordRoutesCustomerToClientAccountNotEmployee() {
        ClientAccountMapper clientAccountMapper = mock(ClientAccountMapper.class);
        when(clientAccountMapper.selectById("ca_1")).thenReturn(account());
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        com.cqwlw.maintenance.service.ClientAccountService clientService =
                mock(com.cqwlw.maintenance.service.ClientAccountService.class);
        AuthController controller = new AuthController(employeeMapper, mock(JwtService.class),
                mock(WxAuthService.class), mock(ClientLoginService.class),
                mock(UserRoleService.class));
        controller.setClientAccountMapper(clientAccountMapper);
        controller.setClientAccountService(clientService);

        var resp = controller.changePassword(customerRequest("ca_1"),
                java.util.Map.of("oldPassword", "123456", "newPassword", "12345678"));

        assertEquals(0, resp.getCode());
        org.mockito.Mockito.verify(clientService).changePassword("ca_1", "123456", "12345678");
        // 客户改密绝不能落到员工表：此前 currentUser() 返回 null 直接 401，前端清登录态
        verifyNoInteractions(employeeMapper);
    }

    @Test
    void bindWechatRoutesCustomerToClientAccount() {
        ClientAccountMapper clientAccountMapper = mock(ClientAccountMapper.class);
        when(clientAccountMapper.selectById("ca_1")).thenReturn(account());
        EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
        WxAuthService wxAuthService = mock(WxAuthService.class);
        when(wxAuthService.openidFromCode("code-1")).thenReturn("openid-1");
        com.cqwlw.maintenance.service.ClientAccountService clientService =
                mock(com.cqwlw.maintenance.service.ClientAccountService.class);
        AuthController controller = new AuthController(employeeMapper, mock(JwtService.class),
                wxAuthService, mock(ClientLoginService.class),
                mock(UserRoleService.class));
        controller.setClientAccountMapper(clientAccountMapper);
        controller.setClientAccountService(clientService);

        var resp = controller.bindWeChat(java.util.Map.of("code", "code-1"), customerRequest("ca_1"));

        assertEquals(0, resp.getCode());
        org.mockito.Mockito.verify(clientService).bindWechat("ca_1", "openid-1", "ca_1");
        verifyNoInteractions(employeeMapper);
    }

    @Test
    void unitAccountPasswordRoutesCustomerToClientAccountNotUseUnit() {
        com.cqwlw.maintenance.service.UnitAccountService unitAccountService =
                mock(com.cqwlw.maintenance.service.UnitAccountService.class);
        com.cqwlw.maintenance.service.ClientAccountService clientService =
                mock(com.cqwlw.maintenance.service.ClientAccountService.class);
        com.cqwlw.maintenance.controller.UnitAccountController controller =
                new com.cqwlw.maintenance.controller.UnitAccountController(unitAccountService);
        controller.setClientAccountService(clientService);

        var resp = controller.changePassword(
                java.util.Map.of("oldPassword", "123456", "newPassword", "12345678"),
                customerRequest("ca_1"));

        assertEquals(0, resp.getCode());
        org.mockito.Mockito.verify(clientService).changePassword("ca_1", "123456", "12345678");
        // 客户口令在 client_account.password_hash；use_unit.password_hash 迁移后已清空，
        // 走旧口径会 401 并让客户被踢回登录页
        verifyNoInteractions(unitAccountService);
    }
}
