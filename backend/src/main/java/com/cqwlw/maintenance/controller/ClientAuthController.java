package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.JwtService;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.ClientAccount;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.service.ClientAccountService;
import com.cqwlw.maintenance.service.ClientLoginService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户会话端点（P3）：登录已并入唯一入口 {@code POST /auth/login} / {@code POST /auth/wx-login}
 * （AuthController 自动分流，前端不再区分通道）；本控制器只保留「登录后」能力：
 * 选单位、本人信息、自助改密。
 *
 * <p>选单位：口令/微信已验证的 selectionToken（claim sel=1）+ useUnitId → 正式客户令牌（ut=2）；
 * 选定前不暴露单位列表，避免手机号枚举。
 */
@RestController
public class ClientAuthController {

    private final ClientAccountService clientService;
    private final ClientLoginService clientLoginService;
    private final UseUnitMapper useUnitMapper;
    private final JwtService jwtService;

    public ClientAuthController(ClientAccountService clientService, ClientLoginService clientLoginService,
                                UseUnitMapper useUnitMapper, JwtService jwtService) {
        this.clientService = clientService;
        this.clientLoginService = clientLoginService;
        this.useUnitMapper = useUnitMapper;
        this.jwtService = jwtService;
    }

    /** 选单位：凭 selectionToken（口令/微信已验证）+ unitId 签发正式客户令牌。 */
    @PostMapping("/auth/client/select-unit")
    public ApiResponse<Map<String, Object>> selectUnit(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        String selToken = bearer(request);
        Claims claims;
        try {
            claims = jwtService.verify(selToken);
        } catch (BizException e) {
            throw new BizException(401, "选单位凭证已失效，请重新登录");
        }
        if (!"1".equals(claims.get("sel", String.class))) {
            throw new BizException(401, "选单位凭证无效，请重新登录");
        }
        String unitId = text(body.get("useUnitId"));
        if (unitId.isEmpty()) {
            throw new BizException(422, "请选择使用单位");
        }
        return ApiResponse.ok(clientLoginService.selectUnit(claims.getSubject(), unitId));
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
