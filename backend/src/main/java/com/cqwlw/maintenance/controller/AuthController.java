package com.cqwlw.maintenance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** 小程序账号密码登录；微信一键登录在 P2 后续迭代接入。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final EmployeeMapper employeeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(EmployeeMapper employeeMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.employeeMapper = employeeMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String phone = body.getOrDefault("phone", "").trim();
        String password = body.getOrDefault("password", "");
        if (!phone.matches("^1\\d{10}$")) {
            return ApiResponse.error(422, "请输入 11 位手机号账号");
        }
        Employee employee = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>().eq(Employee::getPhone, phone));
        if (employee == null || !Boolean.TRUE.equals(employee.getActive())) {
            throw new com.cqwlw.maintenance.common.BusinessException(401, "账号不存在或已停用");
        }
        if (!passwordEncoder.matches(password, employee.getPasswordHash())) {
            throw new com.cqwlw.maintenance.common.BusinessException(401, "账号或密码错误");
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", employee.getId());
        userInfo.put("name", employee.getName());
        userInfo.put("phone", employee.getPhone());
        userInfo.put("role", employee.getRole());
        userInfo.put("roleText", employee.getRoleText());
        userInfo.put("platformId", employee.getPlatformId());
        userInfo.put("certificate", employee.getCertificate());

        Map<String, Object> data = new HashMap<>();
        data.put("token", jwtService.issue(employee.getPhone(), employee.getRole()));
        data.put("userInfo", userInfo);
        data.put("role", employee.getRole());
        return ApiResponse.ok(data);
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout() {
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/bind-wechat")
    public ApiResponse<Map<String, Object>> bindWeChat(@RequestBody Map<String, String> body) {
        if (body.get("code") == null || body.get("code").isBlank()) {
            return ApiResponse.error(422, "缺少微信 code");
        }
        return ApiResponse.ok(Map.of("ok", true, "message", "微信登录后置到 P2"));
    }
}
