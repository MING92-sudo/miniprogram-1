package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.PlatformProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
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
    /** 2.3/2.4 文件转发专用：读超时 120s（docs/04 A.0 大文件口径），避免默认 30s 掐断大附件 */
    private final RestTemplate uploadRestTemplate = newUploadRestTemplate(120_000);

    public PlatformClient(PlatformProperties props, RestTemplate restTemplate, PlatformTokenService tokenService) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.tokenService = tokenService;
    }

    private static RestTemplate newUploadRestTemplate(int readTimeoutMillis) {
        org.springframework.http.client.SimpleClientHttpRequestFactory f =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(readTimeoutMillis);
        return new RestTemplate(f);
    }

    /** 供管理端在未配凭证时给出友好提示（不触发真实调用） */
    public boolean configured() {
        return tokenService.configured();
    }

    /** 2.2 通用主体查询：返回 data[0].entityID（实测业务数据在 data 数组中） */
    public String queryEntityId(String organizationCode, String unitName) {
        return String.valueOf(queryEntity(organizationCode, unitName).get("entityID"));
    }

    /** 2.2 通用主体查询：返回 data[0] 原始记录（entityID/unitName/organizationCode）——unitName 为平台侧权威单位名称 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> queryEntity(String organizationCode, String unitName) {
        MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("organizationCode", organizationCode);
        if (unitName != null && !unitName.isEmpty()) {
            form.add("unitName", unitName);
        }
        Map<String, Object> body = postForm("/entity/queryID", form);
        Object data = body.get("data");
        if (data instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> first && first.get("entityID") != null) {
            return (Map<String, Object>) first;
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

    /**
     * 2.6 记录上报（docs/07-full-test 实测：POST /elevator/maintenanceRecord，20 字段表单）。
     * reportPayload 为签退冻结快照，直接取用不重新组装（docs/08 V1.4）；
     * problemCode 传 JSON 数组字符串（docs/04 B.6 V1.1：数组是确定答案）。
     */
    public Map<String, Object> uploadMaintenanceRecord(Map<String, Object> payload) {
        MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        payload.forEach((k, v) -> {
            if (v == null) {
                return;
            }
            if ("problemCode".equals(k)) {
                form.add(k, json(v));
            } else {
                form.add(k, String.valueOf(v));
            }
        });
        return postForm("/elevator/maintenanceRecord", form);
    }

    /** 2.8 存量上报（POST /record/uploadMaintainRecord）：与 2.6 报文差异——不带 workMan1Id/2Id，
     * 须传 workManName1/2 姓名（规范必填） */
    public Map<String, Object> uploadLegacyRecord(Map<String, Object> payload,
                                                  String workManName1, String workManName2) {
        Map<String, Object> legacy = new java.util.LinkedHashMap<>(payload);
        legacy.remove("workMan1Id");
        legacy.remove("workMan2Id");
        legacy.put("workManName1", workManName1 == null ? "" : workManName1);
        legacy.put("workManName2", workManName2 == null ? "" : workManName2);
        return postForm("/record/uploadMaintainRecord", toForm(legacy));
    }

    /** 2.5 人员列表查询（POST /entity/queryWorkList；按证书号精确匹配 platform_id） */
    public List<Map<String, Object>> queryWorkList(String changState, String workEndDate) {
        MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("changState", changState == null || changState.isEmpty() ? "0" : changState);
        if (workEndDate != null && !workEndDate.isEmpty()) {
            form.add("workEndDate", workEndDate);
        }
        Map<String, Object> body = postForm("/entity/queryWorkList", form);
        Object data = body.get("data");
        if (data instanceof List<?> list) {
            return list.stream().filter(x -> x instanceof Map)
                    .map(x -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) x;
                        return m;
                    }).toList();
        }
        return List.of();
    }

    /** 2.3 建立维保服务关系（multipart + contractFile，参数名按实测 useUnitName，docs/07） */
    public Map<String, Object> registerServiceState(Map<String, String> fields,
                                                    byte[] contractFile, String contractFilename) {
        return postMultipart("/entity/updateServiceState", fields, "contractFile",
                contractFile, contractFilename);
    }

    /** 2.4 登记维保人员（multipart + certificateFile，docs/07 实测口径） */
    public Map<String, Object> registerWorkerState(Map<String, String> fields,
                                                   byte[] certificateFile, String certificateFilename) {
        return postMultipart("/entity/updateWorkState", fields, "certificateFile",
                certificateFile, certificateFilename);
    }

    private static MultiValueMap<String, String> toForm(Map<String, Object> payload) {
        MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        payload.forEach((k, v) -> {
            if (v == null) {
                return;
            }
            if ("problemCode".equals(k)) {
                form.add(k, json(v));
            } else {
                form.add(k, String.valueOf(v));
            }
        });
        return form;
    }

    private static String json(Object v) {
        try {
            return MAPPER.writeValueAsString(v);
        } catch (Exception e) {
            throw new BizException(422, "报文序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> postMultipart(String path, Map<String, String> fields,
                                              String fileField, byte[] file, String filename) {
        if (props.getApiBaseUrl() == null || props.getApiBaseUrl().isEmpty()) {
            throw new BizException(2001, "监管平台地址未配置（REG_API_BASE_URL）");
        }
        String token = tokenService.getToken();
        try {
            return exchangeMultipart(path, fields, fileField, file, filename, token);
        } catch (TokenExpired e) {
            String fresh = tokenService.getToken();
            if (fresh.equals(token)) {
                throw new BizException(2003, "监管平台认证失败");
            }
            return exchangeMultipart(path, fields, fileField, file, filename, fresh);
        }
    }

    private Map<String, Object> exchangeMultipart(String path, Map<String, String> fields,
                                                  String fileField, byte[] file, String filename,
                                                  String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        fields.forEach((k, v) -> {
            if (v != null) {
                body.add(k, v);
            }
        });
        if (file != null) {
            ByteArrayResource resource = new ByteArrayResource(file) {
                @Override
                public String getFilename() {
                    return filename == null ? "file.pdf" : filename;
                }
            };
            body.add(fileField, resource);
        }
        String url = props.getApiBaseUrl() + path;
        try {
            ResponseEntity<String> resp = uploadRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            Map<String, Object> parsed = MAPPER.readValue(resp.getBody(),
                    new TypeReference<Map<String, Object>>() {
                    });
            if (!"200".equals(String.valueOf(parsed.get("code")).trim())) {
                log.warn("平台接口调用失败: path={}, code={}", path, parsed.get("code"));
                throw new BizException(2002, "平台返回错误: " + parsed.get("message"));
            }
            return parsed;
        } catch (BizException e) {
            throw e;
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new TokenExpired();
        } catch (Exception e) {
            log.warn("平台接口调用异常: path={}, {}", path, e.getMessage());
            throw new BizException(2002, "平台接口调用失败");
        }
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
