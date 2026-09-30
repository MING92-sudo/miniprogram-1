package com.cqwlw.maintenance.service;

import org.springframework.stereotype.Service;

/**
 * 监管平台业务接口转发层。
 * 小程序只调本后端，本后端统一携带平台 token 转发到 REG_API_BASE_URL。
 */
@Service
public class PlatformClient {

    /**
     * 转发业务请求到平台。
     * TODO 按各业务接口实现表单参数拼装（平台为 x-www-form-urlencoded，非 JSON）。
     */
    public String forward(String path, Object payload) {
        throw new UnsupportedOperationException("平台转发尚未实现: " + path);
    }
}
