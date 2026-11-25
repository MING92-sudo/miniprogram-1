package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.ClientAccountService;
import com.cqwlw.maintenance.service.ClientLoginService;
import com.cqwlw.maintenance.service.WxAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 统一登录（用户需求）：小程序只有一个登录页，不选通道。 */
class UnifiedLoginTest {

    private final EmployeeMapper employeeMapper = mock(EmployeeMapper.class);
    private final ClientAccountService clientAccountService = mock(ClientAccountService.class);
    private final UseUnitMapper useUnitMapper = mock(UseUnitMapper.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final UserRoleService userRoleService = mock(UserRoleService.class);
    private final WxAuthService wxAuthService = mock(WxAuthService.class);
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

    /** 被测控制器使用真实 ClientLoginService（分流判定本身要被覆盖，不能 mock 掉） */
    private final ClientLoginService clientLoginService =
            new ClientLoginService(clientAccountService, useUnitMapper, jwtService);

    private AuthController controller() {
        return new AuthController(employeeMapper, jwtService, wxAuthService,
                clientLoginService, userRoleService);
    }

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest("POST", "/auth/login");
    }

    // ── 员工分支 ───────────────────────────────────────────────

    /** 员工账号：自动走员工 JWT，响应 EMPLOYEE + 维保工作台 */
    @Test
    void employeeAccountRoutedToEmployeeSession() {
        Employee e = new Employee();
        e.id = "emp_1";
        e.name = "张伟";
        e.role = "WORKER";
        e.account = "13800000001";
        e.passwordHash = enc.encode("123456");
        e.enabled = true;
        when(employeeMapper.selectOne(any())).thenReturn(e);
        when(userRoleService.canonicalRolesOf(any(), any())).thenReturn(Set.of("MAINT_TECH"));
        when(jwtService.issue(anyString(), anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn("emp-token");

        Map<String, Object> data = controller().login(
                Map.of("phone", "13800000001", "password", "123456"), req()).getData();

        assertEquals("emp-token", data.get("token"));
        assertEquals("EMPLOYEE", data.get("principalType"));
        assertEquals("/pages/maint/home/index", data.get("home"));
        assertEquals(List.of("MAINT_TECH"), data.get("roles"));
    }

    /** 混合角色（内部 + 客户）仍是员工主体：isCustomerPrincipal 只在「全客户」时收敛 */
    @Test
    void mixedRoleStaysEmployeePrincipal() {
        Employee e = new Employee();
        e.id = "emp_m";
        e.role = "MAINT_TECH";
        e.account = "13800000099";
        e.passwordHash = enc.encode("123456");
        e.enabled = true;
        when(employeeMapper.selectOne(any())).thenReturn(e);
        when(userRoleService.canonicalRolesOf(any(), any()))
                .thenReturn(Set.of("MAINT_TECH", "CLIENT_SIGNER"));
        when(jwtService.issue(anyString(), anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn("mixed-token");

        Map<String, Object> data = controller().login(
                Map.of("phone", "13800000099", "password", "123456"), req()).getData();

        assertEquals("EMPLOYEE", data.get("principalType"));
        assertTrue(((List<?>) data.get("roles")).containsAll(List.of("MAINT_TECH", "CLIENT_SIGNER")));
    }

    // ── 客户分支（同一登录页自动识别）────────────────────────────

    /** 客户账号：员工目录查不到 → client_account 验口令 → CUSTOMER + 客户工作台，无需切换模式 */
    @Test
    void clientAccountRoutedToCustomerSession() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientAccountService.findByPhone("13800000003"))
                .thenReturn(List.of(client("ca_1", "uu_1", "13800000003", "000003")));
        when(useUnitMapper.selectById("uu_1")).thenReturn(unit("uu_1", "重庆世纪物业管理有限公司"));
        when(jwtService.issueClient(anyString(), anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn("client-token");

        Map<String, Object> data = controller().login(
                Map.of("phone", "13800000003", "password", "000003"), req()).getData();

        assertEquals("client-token", data.get("token"));
        assertEquals("CUSTOMER", data.get("principalType"));
        assertEquals("CLIENT_ADMIN", data.get("role"));
        assertEquals(List.of("CLIENT_ADMIN"), data.get("roles"));
        assertEquals("uu_1", data.get("useUnitId"));
        assertEquals("/pages/client/home/index", data.get("home"));
        assertEquals("CLIENT", data.get("activeWorkspace"));
        assertTrue(String.valueOf(data.get("scope")).contains("重庆世纪物业"));
        assertEquals(false, data.get("needSelectUnit"));
    }

    /** 同手机号多单位：口令验证通过后返回候选，前端跳选单位页（不泄露单位给未验证者） */
    @Test
    void clientWithMultipleUnitsNeedsSelection() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientAccountService.findByPhone("13800000003")).thenReturn(List.of(
                client("ca_1", "uu_1", "13800000003", "000003"),
                client("ca_9", "uu_9", "13800000003", "000003")));
        when(useUnitMapper.selectById("uu_1")).thenReturn(unit("uu_1", "重庆世纪物业"));
        when(useUnitMapper.selectById("uu_9")).thenReturn(unit("uu_9", "重庆蓝湾物业"));
        when(jwtService.issueSelection("13800000003")).thenReturn("sel-token");

        Map<String, Object> data = controller().login(
                Map.of("phone", "13800000003", "password", "000003"), req()).getData();

        assertEquals(true, data.get("needSelectUnit"));
        assertEquals("sel-token", data.get("selectionToken"));
        assertEquals("CUSTOMER", data.get("principalType"));
        assertEquals(2, ((List<?>) data.get("units")).size());
    }

    /** 客户账号已停用：403 明确提示，不能落到「账号或密码错误」 */
    @Test
    void disabledClientAccountRejected() {
        ClientAccount c = client("ca_1", "uu_1", "13800000003", "000003");
        c.status = ClientAccount.STATUS_DISABLED;
        c.enabled = 0;
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientAccountService.findByPhone("13800000003")).thenReturn(List.of(c));

        BizException ex = assertThrows(BizException.class, () -> controller().login(
                Map.of("phone", "13800000003", "password", "000003"), req()));

        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("停用"));
    }

    /** 客户口令错误：401（不区分账号/口令，避免枚举） */
    @Test
    void clientWrongPasswordRejected() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientAccountService.findByPhone("13800000003"))
                .thenReturn(List.of(client("ca_1", "uu_1", "13800000003", "000003")));

        BizException ex = assertThrows(BizException.class, () -> controller().login(
                Map.of("phone", "13800000003", "password", "wrong"), req()));

        assertEquals(401, ex.getCode());
    }

    /** 两侧都查不到：401 账号或密码错误 */
    @Test
    void unknownAccountRejected() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientAccountService.findByPhone("13900000000")).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class, () -> controller().login(
                Map.of("phone", "13900000000", "password", "123456"), req()));

        assertEquals(401, ex.getCode());
    }

    @Test
    void legacyCustomerEmployeeRowBypassedToClientAccount() {
        Employee legacy = new Employee();
        legacy.id = "unit_1";
        legacy.role = "UNIT_ADMIN";
        legacy.account = "13800000003";
        legacy.passwordHash = enc.encode("123456");
        legacy.enabled = true;
        when(employeeMapper.selectOne(any())).thenReturn(legacy);
        when(userRoleService.canonicalRolesOf(any(), any())).thenReturn(Set.of("CLIENT_ADMIN"));
        when(clientAccountService.findByPhone("13800000003"))
                .thenReturn(List.of(client("ca_1", "uu_1", "13800000003", "000003")));
        when(useUnitMapper.selectById("uu_1")).thenReturn(unit("uu_1", "重庆世纪物业"));
        when(jwtService.issueClient(anyString(), anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn("client-token");

        BizException oldPwd = assertThrows(BizException.class, () -> controller().login(
                Map.of("phone", "13800000003", "password", "123456"), req()));
        assertEquals(401, oldPwd.getCode());

        Map<String, Object> data = controller().login(
                Map.of("phone", "13800000003", "password", "000003"), req()).getData();
        assertEquals("CUSTOMER", data.get("principalType"));
        assertEquals("client-token", data.get("token"));
    }

    /** 管理端（client=admin）不接受客户账号：客户只在小程序使用 */
    @Test
    void clientAccountCannotUseAdminConsole() {
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientAccountService.findByPhone("13800000003"))
                .thenReturn(List.of(client("ca_1", "uu_1", "13800000003", "000003")));

        BizException ex = assertThrows(BizException.class, () -> controller().login(
                Map.of("phone", "13800000003", "password", "000003", "client", "admin"), req()));

        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("小程序"));
    }

    /** M3：连续 5 次口令错误后第 6 次请求被限流 429（锁定期间不再走账号校验） */
    @Test
    void loginRateLimitedAfterRepeatedFailures() {
        AuthController controller = controller();
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientAccountService.findByPhone("13900000000")).thenReturn(List.of());

        for (int i = 0; i < 5; i++) {
            int round = i;
            BizException ex = assertThrows(BizException.class, () -> controller.login(
                    Map.of("phone", "13900000000", "password", "123456"), req()), "round=" + round);
            assertEquals(401, ex.getCode(), "round=" + round);
        }
        BizException limited = assertThrows(BizException.class, () -> controller.login(
                Map.of("phone", "13900000000", "password", "123456"), req()));
        assertEquals(429, limited.getCode());
    }

    // ── 微信登录（同一入口）────────────────────────────────────

    /** 微信命中客户账号绑定 → CUSTOMER 会话（员工 openid 未命中时不报错，继续查客户） */
    @Test
    void wechatLoginRoutesToClientAccount() {
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_client_1");
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientAccountService.findByOpenid("wx_client_1"))
                .thenReturn(List.of(client("ca_1", "uu_1", "13800000003", "000003")));
        when(useUnitMapper.selectById("uu_1")).thenReturn(unit("uu_1", "重庆世纪物业"));
        when(jwtService.issueClient(anyString(), anyString(), anyString(), any(), anyString(), anyInt()))
                .thenReturn("client-token");

        Map<String, Object> data = controller().wxLogin(Map.of("code", "code_ok")).getData();

        assertEquals("CUSTOMER", data.get("principalType"));
        assertEquals("client-token", data.get("token"));
        assertEquals("/pages/client/home/index", data.get("home"));
    }

    /** 微信两侧都未绑定：422（不能 401，避免前端误清登录态） */
    @Test
    void wechatUnboundReturns422() {
        when(wxAuthService.openidFromCode("code_ok")).thenReturn("wx_unknown");
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(clientAccountService.findByOpenid("wx_unknown")).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class,
                () -> controller().wxLogin(Map.of("code", "code_ok")));

        assertEquals(422, ex.getCode());
    }

    // ── 工具 ─────────────────────────────────────────────────

    private ClientAccount client(String id, String unitId, String phone, String rawPassword) {
        ClientAccount c = new ClientAccount();
        c.id = id;
        c.useUnitId = unitId;
        c.phone = phone;
        c.name = "王芳";
        c.passwordHash = enc.encode(rawPassword);
        c.roleCode = ClientAccount.ROLE_ADMIN;
        c.status = ClientAccount.STATUS_ACTIVE;
        c.enabled = 1;
        c.scopeVersion = 1;
        return c;
    }

    private UseUnit unit(String id, String name) {
        UseUnit u = new UseUnit();
        u.id = id;
        u.unitName = name;
        return u;
    }
}
