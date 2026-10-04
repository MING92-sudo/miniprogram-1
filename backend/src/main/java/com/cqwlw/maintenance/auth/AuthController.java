package com.cqwlw.maintenance.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证（docs/04 A.1 契约）：账号密码登录（BCrypt）→ 自建 JWT；
 * 云托管免鉴权 X-WX-OPENID 绑定；wx-login 仅限已绑定 openid 的账号（phoneCode 解析后置，Q8=A）。
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final EmployeeMapper employeeMapper;
    private final JwtService jwtService;
    private final AppProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(EmployeeMapper employeeMapper, JwtService jwtService, AppProperties props) {
        this.employeeMapper = employeeMapper;
        this.jwtService = jwtService;
        this.props = props;
    }

    @PostMapping("/auth/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                                  @RequestHeader(value = "X-WX-OPENID", required = false) String openidHeader) {
        String phone = body.get("phone") == null ? "" : String.valueOf(body.get("phone")).trim();
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getAccount, phone).last("LIMIT 1"));
        if (user == null) {
            throw new BizException(401, "账号不存在，请联系维保单位管理员分配");
        }
        if (!encoder.matches(password, user.passwordHash)) {
            throw new BizException(401, "账号或密码错误");
        }
        if (Boolean.FALSE.equals(user.enabled)) {
            throw new BizException(403, "账号已停用，请联系系统管理员");
        }
        // 管理端会话白名单（docs/09 §3.2）：client=admin 时仅 LEADER/ADMIN/SYS_ADMIN 可登录；
        // 小程序端（不带 client）不受影响。
        if ("admin".equals(String.valueOf(body.get("client")))) {
            if (!AdminRoles.canUseAdminConsole(user.role)) {
                throw new BizException(403, "该账号无管理端权限，请使用小程序登录");
            }
        }
        String openid = openidHeader != null && !openidHeader.isEmpty() ? openidHeader : props.getDevOpenid();
        return ApiResponse.ok(loginResult(user, openid));
    }

    @PostMapping("/auth/bind-wechat")
    public ApiResponse<Map<String, Object>> bindWeChat(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request,
                                                       @RequestHeader(value = "X-WX-OPENID", required = false) String openidHeader) {
        if (body.get("code") == null || String.valueOf(body.get("code")).isEmpty()) {
            throw new BizException(422, "缺少微信 code");
        }
        String openid = openidHeader != null && !openidHeader.isEmpty() ? openidHeader : props.getDevOpenid();
        Employee current = currentUser(request);
        if (current != null) {
            current.openid = openid;
            employeeMapper.updateById(current);
            log.info("微信绑定成功: empId={}", current.id);
            return ApiResponse.ok(Map.of("ok", true, "openid", openid));
        }
        return ApiResponse.ok(Map.of("ok", true, "openid", openid));
    }

    @PostMapping("/auth/wx-login")
    public ApiResponse<Map<String, Object>> wxLogin(@RequestBody Map<String, Object> body,
                                                    @RequestHeader(value = "X-WX-OPENID", required = false) String openidHeader) {
        String openid = openidHeader != null && !openidHeader.isEmpty() ? openidHeader : props.getDevOpenid();
        Employee user = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getOpenid, openid).last("LIMIT 1"));
        if (user == null) {
            throw new BizException(401, "该微信尚未绑定账号，请先用账号密码登录并绑定微信");
        }
        if (Boolean.FALSE.equals(user.enabled)) {
            throw new BizException(403, "账号已停用，请联系系统管理员");
        }
        return ApiResponse.ok(loginResult(user, openid));
    }

    @PostMapping("/auth/bind-employee")
    public ApiResponse<Map<String, Object>> bindEmployee(@RequestBody(required = false) Map<String, Object> body,
                                                         HttpServletRequest request,
                                                         @RequestHeader(value = "X-WX-OPENID", required = false) String openidHeader) {
        String role = body != null && "LEADER".equals(body.get("role")) ? "LEADER" : "WORKER";
        return ApiResponse.ok(roleLogin(request, openidHeader, role, false));
    }

    @PostMapping("/auth/bind-use-unit")
    public ApiResponse<Map<String, Object>> bindUseUnit(HttpServletRequest request,
                                                        @RequestHeader(value = "X-WX-OPENID", required = false) String openidHeader) {
        return ApiResponse.ok(roleLogin(request, openidHeader, "UNIT_ADMIN", true));
    }

    /** 用户需求④：登录用户自助改密（新密码 ≥6 位；既有 JWT 不失效，重新登录用新密码） */
    @PostMapping("/auth/change-password")
    public ApiResponse<Map<String, Object>> changePassword(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Employee user = currentUser(request);
        if (user == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        String oldPassword = body.get("oldPassword") == null ? "" : String.valueOf(body.get("oldPassword"));
        String newPassword = body.get("newPassword") == null ? "" : String.valueOf(body.get("newPassword"));
        if (!encoder.matches(oldPassword, user.passwordHash)) {
            throw new BizException(422, "原密码不正确");
        }
        if (newPassword.length() < 6) {
            throw new BizException(422, "新密码至少 6 位");
        }
        if (newPassword.equals(oldPassword)) {
            throw new BizException(422, "新密码不能与原密码相同");
        }
        user.passwordHash = encoder.encode(newPassword);
        employeeMapper.updateById(user);
        return ApiResponse.ok(Map.of("ok", true));
    }
    @PostMapping("/auth/logout")
    public ApiResponse<Map<String, Object>> logout() {
        return ApiResponse.ok(Map.of("ok", true));
    }

    @GetMapping("/auth/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        Employee user = currentUser(request);
        if (user == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        String role = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_ROLE));
        return ApiResponse.ok(Map.of("userInfo", userInfo(user), "role", role));
    }

    private Map<String, Object> roleLogin(HttpServletRequest request, String openidHeader, String role,
                                          boolean forceUnitAdmin) {
        Employee current = currentUser(request);
        if (current == null) {
            throw new BizException(401, "请先登录后再切换角色");
        }
        String effectiveRole = forceUnitAdmin ? "UNIT_ADMIN" : (role.equals(current.role) ? current.role : role);
        String openid = openidHeader != null && !openidHeader.isEmpty() ? openidHeader : props.getDevOpenid();
        Map<String, Object> out = loginResult(current, openid);
        out.put("role", effectiveRole);
        out.put("token", issueSessionToken(current, effectiveRole, openid));
        return out;
    }

    private Map<String, Object> loginResult(Employee user, String openid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", issueSessionToken(user, user.role, openid));
        out.put("userInfo", userInfo(user));
        out.put("role", user.role);
        return out;
    }

    /** 单端登录：每次签发覆盖 sys_employee.session_id，旧 token 由拦截器 401 */
    private String issueSessionToken(Employee user, String role, String openid) {
        String sid = Ids.next("sess");
        user.sessionId = sid;
        employeeMapper.updateById(user);
        return jwtService.issue(user.id, role, openid, sid);
    }

    private Map<String, Object> userInfo(Employee user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.id);
        m.put("name", user.name);
        m.put("phone", user.phone);
        m.put("account", user.account);
        m.put("role", user.role);
        m.put("roleText", user.roleText);
        m.put("platformId", user.platformId);
        m.put("certificate", user.certificate);
        m.put("workStartDate", user.workStartDate);
        m.put("workEndDate", user.workEndDate);
        m.put("workStat", user.workStat);
        m.put("syncStatus", user.syncStatus);
        return m;
    }

    private Employee currentUser(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        try {
            Claims claims = jwtService.verify(auth.substring(7).trim());
            return employeeMapper.selectById(claims.getSubject());
        } catch (BizException e) {
            return null;
        }
    }
}
