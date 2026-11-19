package com.cqwlw.maintenance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.JwtService;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.ClientAccountService;
import com.cqwlw.maintenance.service.WxAuthService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户账号认证（P3，docs/14 §6.4）：独立于员工登录入口。
 * 安全：先验口令 → 命中多单位返回 selectionToken + 候选单位（口令未验证前不返回单位列表）；
 * 选定单位后签发正式客户令牌（ut=2）；openid 只信服务端 code 换取；单会话；停用账号拒绝。
 * 客户账号使用 /unit/** 兼容数据域期间的 profile 收敛由小程序侧 token principalType 驱动。
 */
@RestController
public class ClientAuthController {

    private final ClientAccountService clientService;
    private final UseUnitMapper useUnitMapper;
    private final JwtService jwtService;
    private final WxAuthService wxAuthService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public ClientAuthController(ClientAccountService clientService, UseUnitMapper useUnitMapper,
                                JwtService jwtService, WxAuthService wxAuthService) {
        this.clientService = clientService;
        this.useUnitMapper = useUnitMapper;
        this.jwtService = jwtService;
        this.wxAuthService = wxAuthService;
    }

    /** 口令登录：验口令通过后才返回候选单位；单一单位可直接签发，多单位给 selectionToken。 */
    @PostMapping("/auth/client/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String phone = text(body.get("phone"));
        String password = text(body.get("password"));
        List<ClientAccount> accounts = clientService.findByPhone(phone);
        List<ClientAccount> matched = new ArrayList<>();
        for (ClientAccount c : accounts) {
            if (!c.isActive()) {
                continue;
            }
            if (!encoder.matches(password, c.passwordHash)) {
                continue;
            }
            matched.add(c);
        }
        if (matched.isEmpty()) {
            throw new BizException(401, "账号或密码错误");
        }
        return ApiResponse.ok(resolveLogin(phone, matched, null));
    }

    /** 微信登录：code 换 openid（服务端），命中候选需再验单位；openid 不落入客户口令校验。 */
    @PostMapping("/auth/client/wx-login")
    public ApiResponse<Map<String, Object>> wxLogin(@RequestBody Map<String, Object> body) {
        String openid = wxAuthService.openidFromCode(text(body.get("code")));
        List<ClientAccount> matched = new ArrayList<>();
        for (ClientAccount c : clientService.findByOpenid(openid)) {
            if (c.isActive()) {
                matched.add(c);
            }
        }
        if (matched.isEmpty()) {
            throw new BizException(422, "该微信尚未绑定客户账号，请联系使用单位管理员");
        }
        String phone = matched.get(0).phone;
        return ApiResponse.ok(resolveLogin(phone, matched, openid));
    }

    /** 选单位：凭 selectionToken（口令/微信已验证）+ unitId 签发正式客户令牌。 */
    @PostMapping("/auth/client/select-unit")
    public ApiResponse<Map<String, Object>> selectUnit(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        String selToken = bearer(request);
        Claims claims = jwtService.verify(selToken);
        if (!"1".equals(claims.get("sel", String.class))) {
            throw new BizException(401, "选单位凭证无效，请重新登录");
        }
        String phone = claims.getSubject();
        String unitId = text(body.get("useUnitId"));
        if (unitId.isEmpty()) {
            throw new BizException(422, "请选择使用单位");
        }
        ClientAccount chosen = null;
        for (ClientAccount c : clientService.findByPhone(phone)) {
            if (c.isActive() && unitId.equals(c.useUnitId)) {
                chosen = c;
                break;
            }
        }
        if (chosen == null) {
            throw new BizException(1403, "该单位下无此客户账号");
        }
        return ApiResponse.ok(issueToken(chosen, null));
    }

    /** 客户自助改密：需客户令牌，验原口令，新口令 ≥6 位，清会话。 */
    @PostMapping("/auth/client/change-password")
    public ApiResponse<Map<String, Object>> changePassword(@RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        ClientAccount c = current(request);
        clientService.changePassword(c.id, text(body.get("oldPassword")), text(body.get("newPassword")));
        return ApiResponse.ok(Map.of("ok", true));
    }

    /** 客户本人信息（口令/令牌已校验后的安全投影） */
    @GetMapping("/auth/client/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        ClientAccount c = current(request);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("name", c.name);
        m.put("phone", c.phone);
        m.put("role", c.roleCode);
        m.put("useUnitId", c.useUnitId);
        UseUnit unit = useUnitMapper.selectById(c.useUnitId);
        m.put("unitName", unit == null ? "" : unit.unitName);
        m.put("status", c.status);
        m.put("principalType", RoleCatalog.PrincipalType.CUSTOMER.name());
        return ApiResponse.ok(m);
    }

    // ── 内部方法 ───────────────────────────────────────────────

    /** 单一单位直接签发；多单位返回 needSelectUnit + selectionToken + 候选（口令已验证才到这里） */
    private Map<String, Object> resolveLogin(String phone, List<ClientAccount> matched, String openid) {
        if (matched.size() == 1) {
            return issueToken(matched.get(0), openid);
        }
        List<Map<String, Object>> units = new ArrayList<>();
        for (ClientAccount c : matched) {
            UseUnit u = useUnitMapper.selectById(c.useUnitId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("useUnitId", c.useUnitId);
            item.put("unitName", u == null ? "" : u.unitName);
            item.put("accountId", c.id);
            item.put("role", c.roleCode);
            units.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("needSelectUnit", true);
        out.put("selectionToken", jwtService.issueSelection(phone));
        out.put("units", units);
        return out;
    }

    private Map<String, Object> issueToken(ClientAccount c, String openid) {
        String sid = Ids.next("csess");
        clientService.issueSession(c.id, sid);
        c.sessionId = sid;
        int sv = c.scopeVersion == null ? 1 : c.scopeVersion;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwtService.issueClient(c.id, c.roleCode, c.useUnitId, openid, sid, sv));
        out.put("principalType", RoleCatalog.PrincipalType.CUSTOMER.name());
        out.put("role", c.roleCode);
        out.put("useUnitId", c.useUnitId);
        out.put("needSelectUnit", false);
        UseUnit unit = useUnitMapper.selectById(c.useUnitId);
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", c.id);
        userInfo.put("name", c.name);
        userInfo.put("phone", c.phone);
        userInfo.put("unitName", unit == null ? "" : unit.unitName);
        out.put("userInfo", userInfo);
        // 客户最小能力集三码（docs/14 §4.7，权限 P1 已收敛；随令牌下发供小程序渲染）
        out.put("perms", List.of("client:elevator_view", "client:sign", "client:download"));
        return out;
    }

    private ClientAccount current(HttpServletRequest request) {
        Object v = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        if (v == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        ClientAccount c = clientService.findById(String.valueOf(v));
        if (c == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        return c;
    }

    private static String bearer(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return "";
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
