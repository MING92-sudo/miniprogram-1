package com.cqwlw.maintenance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 调用监管平台/LBS 的 HTTP 客户端。
 * 超时按 docs/08 P2：连接 3s / 读 30s（文件接口 120s，P2 无文件转发）。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate platformRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
        return new RestTemplate(f);
    }
}
