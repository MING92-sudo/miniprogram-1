package com.cqwlw.maintenance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * 调用监管平台用的 HTTP 客户端。
     * TODO 按接口文档 A.0 约定配置超时（普通 15s / 上传 120s）与日志拦截器。
     */
    @Bean
    public RestTemplate platformRestTemplate() {
        return new RestTemplate();
    }
}
