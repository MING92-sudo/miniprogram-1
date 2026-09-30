package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BusinessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * 监管平台业务接口转发层。
 * 小程序只调本后端，本后端统一携带平台 token 转发到 REG_API_BASE_URL。
 */
@Service
public class PlatformClient {

    private final PlatformTokenService tokenService;
    private final RestTemplate restTemplate;

    public PlatformClient(PlatformTokenService tokenService, RestTemplate restTemplate) {
        this.tokenService = tokenService;
        this.restTemplate = restTemplate;
    }

    public ResponseEntity<String> postForm(String path, MultiValueMap<String, String> form) {
        try {
            return exchange(path, form);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() != 401) {
                throw e;
            }
            tokenService.invalidate();
            return exchange(path, form);
        }
    }

    private ResponseEntity<String> exchange(String path, MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBearerAuth(tokenService.getToken());
        return restTemplate.postForEntity(path, new HttpEntity<>(form, headers), String.class);
    }
}
