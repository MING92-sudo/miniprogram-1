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

import java.util.Map;
import java.util.Set;

/**
 * 小程序菜单下发端点（M1，规格 §3.4）：GET /me/menus?workspace=。
 * 独立控制器避免改动 AuthController 构造器波及既有测试；管理端 /auth/menus 不动。
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
        Employee me = currentUser(request);
        if (me == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        Set<String> roles = MultiRoleGate.roles(request);
        return ApiResponse.ok(menuService.menusOf(roles, workspace, me));
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