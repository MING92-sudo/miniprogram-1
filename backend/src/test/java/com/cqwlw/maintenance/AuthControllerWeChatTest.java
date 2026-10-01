package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.AuthController;
import com.cqwlw.maintenance.auth.JwtService;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.service.WxAuthService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * openid 串号修复的接口级回归：
 * ① openid 唯一来源是 code → jscode2session；X-WX-OPENID 头与 dev_openid 一律不被信任；
 * ② bind-wechat 必须带登录态，写入前先显式解绑同一 openid 上的其他账号（含历史重复行）；
 * ③ 无 code（即使带伪造请求头）→ 422 且不落库、不发微信请求；
 * ④ wx-login 按真实 openid 查账号，未绑定用 422（不用 401，避免前端清登录态）；
 * ⑤ JWT 不再携带 openid 声明。
 *
 * <p>本环境（DSH 沙箱）Mockito inline mock maker 无法自附加（Byte Buddy external attach 被禁），
 * 故用 JDK 动态代理手写假 Mapper；真实 SQL（unbindOpenidExcept / updateById）由
 * {@link EmployeeMapperDbTest} 在提供 MySQL 时验证。
 */
class AuthControllerWeChatTest {

    private final List<Employee> rows = new ArrayList<>();
    /** wx-login 的 selectOne 按调用顺序返回（null = 查不到）；bind 已不再预查（改用条件 UPDATE） */
    private final LinkedList<Employee> selectOneResults = new LinkedList<>();
    private final List<Employee> updateByIdCalls = new ArrayList<>();
    private final List<String> unbindCalls = new ArrayList<>();

    private MockRestServiceServer server;
    private AppProperties props;
    private JwtService jwtService;
    private WxAuthService wxAuthService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        props = new AppProperties();
        props.setJwtSecret("unit-test-secret-key-please-change-0123456789");
        props.setWxAppid("wxappid123");
        props.setWxAppsecret("wxsecret456");
        jwtService = new JwtService(props);
        wxAuthService = new WxAuthService(props, restTemplate);
        controller = new AuthController(fakeMapper(), jwtService, wxAuthService);
    }

    // ── bind-wechat ─────────────────────────────────────────────

    @Test
    void bindWritesCodeDerivedOpenidAndReleasesAllOtherHolders() {
        Employee a = employee("emp_a", "13800000001");
        Employee b = employee("emp_b", "13800000002");
        Employee c = employee("emp_c", "13800000003");
        b.openid = "openid_A";
        c.openid = "openid_A"; // 历史重复数据：同一 openid 挂在多行上
        rows.add(a);
        rows.add(b);
        rows.add(c);
        expectCodeExchange("code_A", "openid_A");

        Map<String, Object> data = controller.bindWeChat(Map.of("code", "code_A"),
                authorizedRequest("emp_a")).getData();

        assertEquals("openid_A", data.get("openid"));
        assertEquals("openid_A", a.openid);
        assertNull(b.openid, "同一 openid 不能同时挂在两个账号上");
        assertNull(c.openid, "历史重复行也必须一并解绑");
        assertEquals(List.of("openid_A|emp_a"), unbindCalls);
        assertEquals("emp_a", updateByIdCalls.get(updateByIdCalls.size() - 1).id);
        server.verify();
    }

    @Test
    void bindWithoutCodeFailsClosedEvenWithSpoofedOpenidHeader() {
        Employee a = employee("emp_a", "13800000001");
        Employee victim = employee("emp_victim", "13800000002");
        victim.openid = "openid_victim";
        rows.add(a);
        rows.add(victim);
        MockHttpServletRequest spoofed = authorizedRequest("emp_a");
        spoofed.addHeader("X-WX-OPENID", "openid_victim"); // 方案 A 下任何客户端都能伪造

        BizException e = assertThrows(BizException.class,
                () -> controller.bindWeChat(Map.of(), spoofed));

        assertEquals(422, e.getCode());
        assertNull(a.openid, "不得绑定伪造头里的 openid");
        assertEquals("openid_victim", victim.openid, "受害账号不得被解绑/劫持");
        assertTrue(updateByIdCalls.isEmpty());
        assertTrue(unbindCalls.isEmpty());
        server.verify(); // 未调用微信接口
    }

    @Test
    void bindWithoutLoginTokenIsRejectedAndPersistsNothing() {
        Employee a = employee("emp_a", "13800000001");
        rows.add(a);

        BizException e = assertThrows(BizException.class,
                () -> controller.bindWeChat(Map.of("code", "code_A"), new MockHttpServletRequest()));

        assertEquals(401, e.getCode());
        assertNull(a.openid);
        assertTrue(updateByIdCalls.isEmpty());
        server.verify(); // 未调用微信接口
    }

    @Test
    void bindWithInvalidCodeIsRejectedAndPersistsNothing() {
        Employee a = employee("emp_a", "13800000001");
        rows.add(a);
        expectCodeExchangeError("bad_code", 40029);

        BizException e = assertThrows(BizException.class,
                () -> controller.bindWeChat(Map.of("code", "bad_code"), authorizedRequest("emp_a")));

        // 422 而非 401：401 会让前端清登录态，把刚登录成功的用户踢出去
        assertEquals(422, e.getCode());
        assertNull(a.openid);
        assertTrue(updateByIdCalls.isEmpty());
        assertTrue(unbindCalls.isEmpty());
    }

    @Test
    void bindWithoutWechatCredentialsFailsClosed() {
        AppProperties unconfigured = new AppProperties();
        unconfigured.setJwtSecret("unit-test-secret-key-please-change-0123456789");
        Employee a = employee("emp_a", "13800000001");
        rows.add(a);
        AuthController noWx = new AuthController(fakeMapper(), new JwtService(unconfigured),
                new WxAuthService(unconfigured, new RestTemplate()));

        BizException e = assertThrows(BizException.class,
                () -> noWx.bindWeChat(Map.of("code", "code_A"), authorizedRequest("emp_a")));

        assertEquals(2001, e.getCode());
        assertNull(a.openid, "不得写入任何兜底 openid");
        assertTrue(updateByIdCalls.isEmpty());
    }

    // ── wx-login ────────────────────────────────────────────────

    @Test
    void wxLoginResolvesAccountByCodeDerivedOpenidWithoutOpenidClaim() {
        Employee a = employee("emp_a", "13800000001");
        a.openid = "openid_A";
        rows.add(a);
        selectOneResults.add(a);
        expectCodeExchange("code_A", "openid_A");

        Map<String, Object> data = controller.wxLogin(Map.of("code", "code_A")).getData();

        assertEquals("WORKER", data.get("role"));
        assertNotNull(data.get("token"));
        Claims claims = jwtService.verify(String.valueOf(data.get("token")));
        assertEquals("emp_a", claims.getSubject());
        assertNull(claims.get("openid"), "JWT 不应携带 openid 声明（无消费方，避免伪造值进签名令牌）");
        server.verify();
    }

    @Test
    void wxLoginWithoutCodeFailsClosedEvenWithSpoofedOpenidHeader() {
        // 攻击者 HTTP 请求可自带 X-WX-OPENID: <victim>；wx-login 不接收、不读取任何请求头，没有 code 即失败
        Employee victim = employee("emp_victim", "13800000002");
        victim.openid = "openid_victim";
        rows.add(victim);

        BizException e = assertThrows(BizException.class,
                () -> controller.wxLogin(Map.of()));

        assertEquals(422, e.getCode(), "没有 code 就不能登录，伪造头无效");
        server.verify(); // 不得用请求头里的 openid 去查账号
    }

    @Test
    void wxLoginRejectsUnboundOpenidWith422Not401() {
        selectOneResults.add(null);
        expectCodeExchange("code_X", "openid_X");

        BizException e = assertThrows(BizException.class,
                () -> controller.wxLogin(Map.of("code", "code_X")));

        assertEquals(422, e.getCode(), "401 会触发前端清登录态，一键登录入口不得误登出");
        assertTrue(e.getMessage().contains("尚未绑定"));
    }

    @Test
    void twoEmployeesBindDistinctOpenidsAndEachLogsIntoOwnAccount() {
        // 端到端回归：两人分别绑定 → openid 互不相同 → wx-login 各回各的档案
        Employee a = employee("emp_a", "13800000001");
        Employee b = employee("emp_b", "13800000002");
        rows.add(a);
        rows.add(b);
        selectOneResults.add(b); // wx-login：按 openid_B 查到 B
        expectCodeExchange("code_A", "openid_A");
        expectCodeExchange("code_B", "openid_B");
        expectCodeExchange("code_B", "openid_B");

        controller.bindWeChat(Map.of("code", "code_A"), authorizedRequest("emp_a"));
        controller.bindWeChat(Map.of("code", "code_B"), authorizedRequest("emp_b"));

        assertEquals("openid_A", a.openid);
        assertEquals("openid_B", b.openid);

        Map<String, Object> data = controller.wxLogin(Map.of("code", "code_B")).getData();

        @SuppressWarnings("unchecked")
        Map<String, Object> userInfo = (Map<String, Object>) data.get("userInfo");
        assertEquals("emp_b", userInfo.get("id"));
        server.verify();
    }

    // ── 回归护栏：旧的兜底来源不得复活 ────────────────────────────

    @Test
    void authControllerClassNoLongerReferencesSpoofableOpenidHeader() throws Exception {
        // 常量池里出现 "X-WX-OPENID" 即说明有代码路径又读了该头（含 getHeader 写法）
        byte[] classBytes;
        try (InputStream in = AuthController.class.getResourceAsStream("AuthController.class")) {
            classBytes = in.readAllBytes();
        }
        String constantPool = new String(classBytes, StandardCharsets.ISO_8859_1);
        assertFalse(constantPool.contains("X-WX-OPENID"),
                "P0 修复后 openid 只能来自 code，AuthController 不得再引用 X-WX-OPENID");
    }

    @Test
    void devOpenidConfigIsGone() {
        assertThrows(NoSuchMethodException.class, () -> AppProperties.class.getMethod("getDevOpenid"));
        assertThrows(NoSuchMethodException.class, () -> AppProperties.class.getMethod("setDevOpenid", String.class));
    }

    // ── 夹具 ────────────────────────────────────────────────────

    private MockHttpServletRequest authorizedRequest(String employeeId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtService.issue(employeeId, "WORKER", null));
        return request;
    }

    private static Employee employee(String id, String phone) {
        Employee e = new Employee();
        e.id = id;
        e.name = id;
        e.phone = phone;
        e.account = phone;
        e.passwordHash = "x";
        e.role = "WORKER";
        e.roleText = "维保人员";
        e.enabled = true;
        return e;
    }

    private void expectCodeExchange(String code, String openid) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session")))
                .andExpect(queryParam("js_code", code))
                .andRespond(withSuccess("{\"openid\":\"" + openid + "\"}", MediaType.APPLICATION_JSON));
    }

    private void expectCodeExchangeError(String code, int errcode) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.weixin.qq.com/sns/jscode2session")))
                .andExpect(queryParam("js_code", code))
                .andRespond(withSuccess("{\"errcode\":" + errcode + ",\"errmsg\":\"invalid code\"}",
                        MediaType.APPLICATION_JSON));
    }

    private EmployeeMapper fakeMapper() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "selectById":
                    return rows.stream().filter(r -> r.id.equals(args[0])).findFirst().orElse(null);
                case "selectOne":
                    return selectOneResults.isEmpty() ? null : selectOneResults.poll();
                case "unbindOpenidExcept": {
                    String openid = (String) args[0];
                    String keepId = (String) args[1];
                    unbindCalls.add(openid + "|" + keepId);
                    int cleared = 0;
                    for (Employee r : rows) {
                        if (openid.equals(r.openid) && !keepId.equals(r.id)) {
                            r.openid = null;
                            cleared++;
                        }
                    }
                    return cleared;
                }
                case "updateById": {
                    updateByIdCalls.add((Employee) args[0]);
                    return 1;
                }
                case "insert":
                    rows.add((Employee) args[0]);
                    return 1;
                case "toString":
                    return "FakeEmployeeMapper";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException("测试假 Mapper 未实现: " + method.getName());
            }
        };
        return (EmployeeMapper) Proxy.newProxyInstance(EmployeeMapper.class.getClassLoader(),
                new Class<?>[]{EmployeeMapper.class}, handler);
    }
}
