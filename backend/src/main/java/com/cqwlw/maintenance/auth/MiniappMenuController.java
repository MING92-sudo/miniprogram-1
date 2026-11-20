package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 小程序菜单下发端点（M1，规格 §3.4）：GET /me/menus?workspace=。
 * 独立控制器避免改动 AuthController 构造器波及既有测试。
 * 这是**唯一**菜单下发端点：旧的 GET /auth/menus（MenuService）已于 2026-11-20 删除——
 * 管理端菜单由 admin/src/ui/menu-config.js + permission-v2 本地投影，不需要服务端下发。
 */
@RestController
public class MiniappMenuController {

    private final MiniappMenuService menuService;
    private final EmployeeMapper employeeMapper;
    private final JwtService jwtService;

    public MiniappMenuController(MiniappMenuService menuService, EmployeeMapper employeeMapper,
                                 JwtService jwtService) {
        this.menuService = menuService;
        this.employeeMapper = employeeMapper;
        this.jwtService = jwtService;
    }

    @GetMapping("/me/menus")
    public ApiResponse<Map<String, Object>> meMenus(HttpServletRequest request,
                                                    @RequestParam(required = false) String workspace) {
        // 客户主体（ut=2，subject=client_account.id）不在员工目录里，不能走 employeeMapper 查人，
        // 更不能抛 401：前端 request 层把 401 当「登录已失效」→ clearAuth + 回登录页，
        // 于是客户登录成功后立刻被踢回登录页（小程序实测必现）。客户工作台是固定 tab，这里返回 200 空菜单。
        Object principalType = request.getAttribute(AuthInterceptor.ATTR_PRINCIPAL_TYPE);
        if (RoleCatalog.PrincipalType.CUSTOMER.name().equals(String.valueOf(principalType))) {
            return ApiResponse.ok(clientMenus(request));
        }
        Employee me = currentUser(request);
        if (me == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        Set<String> roles = MultiRoleGate.roles(request);
        return ApiResponse.ok(menuService.menusOf(roles, workspace, me));
    }

    /**
     * 客户主体菜单：结构与管理端/员工端同形状（user/home/activeWorkspace/tabs/quickActions/actions/badges），
     * 但 tabs 为空——客户工作台的 tab 由前端 config/menus 固定下发，后端不参与编排。
     */
    private Map<String, Object> clientMenus(HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> user = new LinkedHashMap<>();
        Object accountId = request.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        if (accountId != null) {
            user.put("id", String.valueOf(accountId));
        }
        user.put("roles", new ArrayList<>());
        out.put("user", user);
        out.put("principalType", RoleCatalog.PrincipalType.CUSTOMER.name());
        out.put("home", "/pages/client/home/index");
        out.put("activeWorkspace", "CLIENT");
        out.put("tabs", new ArrayList<>());
        out.put("quickActions", new ArrayList<>());
        out.put("actions", new LinkedHashMap<>());
        out.put("badges", Map.of("pendingOrders", 0, "pendingSigns", 0));
        return out;
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