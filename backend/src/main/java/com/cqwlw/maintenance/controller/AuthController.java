package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 小程序端登录入口。
 * TODO 对接微信 code2session 换 openid，再映射平台账号/角色，签发自建后端 JWT。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login() {
        // TODO: 实现 code -> openid -> token 下发
        return ApiResponse.error(501, "登录接口尚未实现");
    }
}
