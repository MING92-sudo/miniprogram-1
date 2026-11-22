package com.cqwlw.maintenance;

import com.cqwlw.maintenance.auth.JwtService;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.UnitAccountService;
import com.cqwlw.maintenance.service.WxAuthService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 微信绑定只认服务端换来的 openid。 */
class UnitAccountServiceTest {

    private final UseUnitMapper useUnitMapper = mock(UseUnitMapper.class);
    private final WxAuthService wxAuthService = mock(WxAuthService.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private JwtService jwtService;
    private UnitAccountService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setJwtSecret("unit-test-secret-unit-test-secret-32b");
        props.setJwtExpireHours(72);
        jwtService = new JwtService(props);
        service = new UnitAccountService(useUnitMapper, jwtService, wxAuthService);
    }

    private UseUnit unit() {
        UseUnit u = new UseUnit();
        u.id = "uu_1";
        u.unitName = "重庆渝达物业管理有限公司";
        u.account = "UU000001";
        u.passwordHash = encoder.encode("Passw0rd!23");
        u.inviteCode = "ABC23DEF";
        return u;
    }

    /** 错密码 → 401，且不签发令牌 */
    @Test
    void loginRejectsWrongPassword() {
        when(useUnitMapper.selectOne(any())).thenReturn(unit());
        BizException ex = assertThrows(BizException.class,
                () -> service.login("UU000001", "wrong-password"));
        assertEquals(401, ex.getCode());
    }

    /** 正确口令 → 签发门户令牌：subject=use_unit.id、ut=1、role=UNIT_ADMIN */
    @Test
    void loginIssuesPortalToken() {
        when(useUnitMapper.selectOne(any())).thenReturn(unit());
        Map<String, Object> out = service.login("UU000001", "Passw0rd!23");

        assertNotNull(out.get("token"));
        Claims claims = jwtService.verify(String.valueOf(out.get("token")));
        assertEquals("uu_1", claims.getSubject());
        assertEquals("1", claims.get("ut", String.class));
        assertEquals("UNIT_ADMIN", claims.get("role", String.class));
        assertEquals("uu_1", out.get("useUnitId"));
        assertEquals("重庆渝达物业管理有限公司", out.get("unitName"));
    }

    /** 未开通账号（无口令哈希）不能登录，避免空口令绕过 */
    @Test
    void loginRejectsNotProvisionedAccount() {
        UseUnit u = unit();
        u.passwordHash = null;
        when(useUnitMapper.selectOne(any())).thenReturn(u);
        BizException ex = assertThrows(BizException.class, () -> service.login("UU000001", "anything"));
        assertEquals(401, ex.getCode());
    }

    /** 邀请码绑定微信：openid 只来自服务端 jscode2session，绑定后落库并记时间 */
    @Test
    void bindWechatByInviteCode() {
        UseUnit u = unit();
        when(useUnitMapper.selectOne(any())).thenReturn(u);
        when(wxAuthService.openidFromCode("wx-code-1")).thenReturn("openid_abc");

        Map<String, Object> out = service.bindWechat("ABC23DEF", "wx-code-1");

        assertEquals("openid_abc", u.openid);
        assertNotNull(u.boundAt);
        assertEquals(true, out.get("bound"));
        verify(wxAuthService).openidFromCode("wx-code-1");
        ArgumentCaptor<UseUnit> captor = ArgumentCaptor.forClass(UseUnit.class);
        verify(useUnitMapper).updateById(captor.capture());
        assertEquals("openid_abc", captor.getValue().openid);
    }

    /** 邀请码不存在 → 422（不泄露“有哪些单位”） */
    @Test
    void bindWechatRejectsUnknownInvite() {
        when(useUnitMapper.selectOne(any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.bindWechat("NOPE1234", "wx-code-1"));
        assertEquals(422, ex.getCode());
    }

    /** 开通账号：生成 8 位邀请码 + 12 位初始口令，口令只落 BCrypt 哈希 */
    @Test
    void createAccountGeneratesInviteAndPassword() {
        UseUnit u = unit();
        u.account = null;
        u.passwordHash = null;
        when(useUnitMapper.selectById("uu_1")).thenReturn(u);
        when(useUnitMapper.selectCount(any())).thenReturn(0L);

        Map<String, Object> out = service.createAccount("ADMIN", "uu_1", null, null);

        String account = String.valueOf(out.get("account"));
        String initial = String.valueOf(out.get("initialPassword"));
        assertEquals(8, String.valueOf(out.get("inviteCode")).length());
        assertEquals(12, initial.length());
        assertTrue(account.startsWith("U"));
        assertTrue(encoder.matches(initial, u.passwordHash), "只允许存哈希，不能存明文");
        assertEquals(account, u.account);
    }

    /** 非管理员不能开通账号 → 403 */
    @Test
    void createAccountRequiresAdmin() {
        BizException ex = assertThrows(BizException.class,
                () -> service.createAccount("WORKER", "uu_1", "U1", "Passw0rd!23"));
        assertEquals(403, ex.getCode());
    }

    /** 账号已被占用（另一单位或同单位改号）→ 422 */
    @Test
    void createAccountRejectsDuplicate() {
        when(useUnitMapper.selectById("uu_1")).thenReturn(unit());
        when(useUnitMapper.selectCount(any())).thenReturn(1L);
        BizException ex = assertThrows(BizException.class,
                () -> service.createAccount("ADMIN", "uu_1", "TAKEN001", "Passw0rd!23"));
        assertEquals(422, ex.getCode());
    }

    /** 使用单位不存在 → 404 */
    @Test
    void createAccountRejectsUnknownUnit() {
        when(useUnitMapper.selectById("nope")).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> service.createAccount("ADMIN", "nope", "U1", "Passw0rd!23"));
        assertEquals(1404, ex.getCode());
    }

    /** 微信免密登录：绑过的 openid 直接换门户令牌 */
    @Test
    void loginByWechatIssuesToken() {
        UseUnit u = unit();
        u.openid = "openid_abc";
        when(useUnitMapper.selectOne(any())).thenReturn(u);
        when(wxAuthService.openidFromCode("wx-code-2")).thenReturn("openid_abc");

        Map<String, Object> out = service.loginByWechat("wx-code-2");
        Claims claims = jwtService.verify(String.valueOf(out.get("token")));
        assertEquals("uu_1", claims.getSubject());
        assertEquals("1", claims.get("ut", String.class));
        assertEquals(true, out.get("wechatBound"));
    }

    /** 未绑定的微信 → 422，且不泄露单位信息 */
    @Test
    void loginByWechatRejectsUnbound() {
        when(useUnitMapper.selectOne(any())).thenReturn(null);
        when(wxAuthService.openidFromCode("wx-code-3")).thenReturn("openid_unknown");
        BizException ex = assertThrows(BizException.class, () -> service.loginByWechat("wx-code-3"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("绑定"));
    }

    /** 自助改口令：旧口令正确 → 只更新哈希，明文不落库 */
    @Test
    void changePasswordUpdatesHash() {
        UseUnit u = unit();
        when(useUnitMapper.selectById("uu_1")).thenReturn(u);

        Map<String, Object> out = service.changePassword("uu_1", "Passw0rd!23", "NewPass!45");

        assertEquals(true, out.get("ok"));
        assertTrue(encoder.matches("NewPass!45", u.passwordHash));
        assertTrue(!encoder.matches("Passw0rd!23", u.passwordHash), "旧口令必须失效");
        verify(useUnitMapper).updateById(u);
    }

    /** 旧口令错误 → 422，且不落库 */
    @Test
    void changePasswordRejectsWrongOld() {
        when(useUnitMapper.selectById("uu_1")).thenReturn(unit());
        BizException ex = assertThrows(BizException.class,
                () -> service.changePassword("uu_1", "wrong", "NewPass!45"));
        assertEquals(422, ex.getCode());
        verify(useUnitMapper, org.mockito.Mockito.never()).updateById(any(UseUnit.class));
    }

    /** 新口令不足 6 位 / 与原口令相同 → 422 */
    @Test
    void changePasswordValidatesNewPassword() {
        when(useUnitMapper.selectById("uu_1")).thenReturn(unit());
        assertEquals(422, assertThrows(BizException.class,
                () -> service.changePassword("uu_1", "Passw0rd!23", "12345")).getCode());
        assertEquals(422, assertThrows(BizException.class,
                () -> service.changePassword("uu_1", "Passw0rd!23", "Passw0rd!23")).getCode());
    }

    /** 单位不存在 / 未开通口令 → 401（不给改） */
    @Test
    void changePasswordRejectsUnknownUnit() {
        when(useUnitMapper.selectById("nope")).thenReturn(null);
        assertEquals(401, assertThrows(BizException.class,
                () -> service.changePassword("nope", "x", "NewPass!45")).getCode());
    }

    @Test
    void loginRejectsMigratedAccount() {
        UseUnit u = unit();
        u.accountMigrated = 1;
        when(useUnitMapper.selectOne(any())).thenReturn(u);
        BizException ex = assertThrows(BizException.class, () -> service.login("UU000001", "Passw0rd!23"));
        assertEquals(422, ex.getCode());
    }

    /** 微信免密登录同口径拒绝已迁移账号 */
    @Test
    void loginByWechatRejectsMigratedAccount() {
        UseUnit u = unit();
        u.accountMigrated = 1;
        when(wxAuthService.openidFromCode("code1")).thenReturn("openid1");
        when(useUnitMapper.selectOne(any())).thenReturn(u);
        BizException ex = assertThrows(BizException.class, () -> service.loginByWechat("code1"));
        assertEquals(422, ex.getCode());
    }
}
