package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.PlatformProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static com.cqwlw.maintenance.util.JsonUtil.MAPPER;

/**
 * 平台业务接口转发（docs/04 B.2/B.7 实测口径）：
 * POST + x-www-form-urlencoded 表单；code 兼容数字/字符串 "200"；
 * HTTP 401 → 清 token 缓存重登并重试 1 次。
 */
@Service
public class PlatformClient {

    private static final Logger log = LoggerFactory.getLogger(PlatformClient.class);

    private final PlatformProperties props;
    private final RestTemplate restTemplate;
    private final PlatformTokenService tokenService;

    public PlatformClient(PlatformProperties props, RestTemplate restTemplate, PlatformTokenService tokenService) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.tokenService = tokenService;
    }

    /** 2.2 通用主体查询：返回 data[0].entityID（实测业务数据在 data 数组中） */
    public String queryEntityId(String organizationCode, String unitName) {
        MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("organizationCode", organizationCode);
        if (unitName != null && !unitName.isEmpty()) {
            form.add("unitName", unitName);
        }
        Map<String, Object> body = postForm("/entity/queryID", form);
        Object data = body.get("data");
        if (data instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first && first.get("entityID") != null) {
            return String.valueOf(first.get("entityID"));
        }
        throw new BizException(404, "平台未查询到该单位主体，请核对单位名称与统一社会信用代码");
    }

    /** 2.7 电梯基本信息查询：返回 data 数组 */
    public List<Map<String, Object>> queryElevatorInfo(Map<String, String> conditions) {
        MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        conditions.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                form.add(k, v);
            }
        });
        if (form.isEmpty()) {
            throw new BizException(422, "2.7 查询至少需要一个条件");
        }
        Map<String, Object> body = postForm("/equipment/queryElevatorInfo", form);
        Object data = body.get("data");
        if (data instanceof List<?> list) {
            return list.stream()
                    .filter(x -> x instanceof Map)
                    .map(x -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) x;
                        return m;
                    })
                    .toList();
        }
        return List.of();
    }

    private Map<String, Object> postForm(String path, MultiValueMap<String, String> form) {
        if (props.getApiBaseUrl() == null || props.getApiBaseUrl().isEmpty()) {
            throw new BizException(2001, "监管平台地址未配置（REG_API_BASE_URL）");
        }
        String token = tokenService.getToken();
        try {
            return exchange(path, form, token);
        } catch (TokenExpired e) {
            String fresh = tokenService.getToken();
            if (fresh.equals(token)) {
                throw new BizException(2003, "监管平台认证失败");
            }
            return exchange(path, form, fresh);
        }
    }

    private Map<String, Object> exchange(String path, MultiValueMap<String, String> form, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBearerAuth(token);
        String url = props.getApiBaseUrl() + path;
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
            Map<String, Object> body = MAPPER.readValue(resp.getBody(), new TypeReference<Map<String, Object>>() {
            });
            if (!"200".equals(String.valueOf(body.get("code")).trim())) {
                log.warn("平台接口调用失败: path={}, code={}, message={}", path, body.get("code"), body.get("message"));
                throw new BizException(2002, "平台接口调用失败: " + body.get("message"));
            }
            return body;
        } catch (HttpClientErrorException.Unauthorized e) {
            // HTTP 401：清缓存重登，重试 1 次（docs/04 B.1 实测结论）
            log.info("平台 token 失效，清缓存重登重试: path={}", path);
            tokenService.invalidate();
            throw new TokenExpired();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("平台接口请求异常: path={}, {}", path, e.getMessage());
            throw new BizException(2002, "平台接口调用失败: " + e.getMessage());
        }
    }

    /** 401 重试一次的内部信号 */
    private static class TokenExpired extends RuntimeException {
        TokenExpired() {
            super(null, null, false, false);
        }
    }
}
