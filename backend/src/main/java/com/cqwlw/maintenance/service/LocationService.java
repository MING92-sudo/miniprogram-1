package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

import static com.cqwlw.maintenance.util.JsonUtil.MAPPER;

/**
 * LBS 逆地址解析代理（docs/08 审查 #5 收口）：key 只在后端环境变量，前端不接触 key。
 * auto：有高德 key 优先高德，否则腾讯；任何失败回退坐标文本（与前端原兜底行为一致）。
 */
@Service
public class LocationService {

    private static final Logger log = LoggerFactory.getLogger(LocationService.class);

    private final AppProperties props;
    private final RestTemplate restTemplate;

    public LocationService(AppProperties props, RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
    }

    public Map<String, Object> reverse(double lng, double lat) {
        String fallback = String.format("%.5f, %.5f", lat, lng);
        String amapKey = props.getLbsAmapKey();
        String tencentKey = props.getLbsTencentKey();
        if (amapKey != null && !amapKey.isEmpty()) {
            String addr = amap(amapKey, lng, lat, fallback);
            return Map.of("address", addr, "provider", "amap");
        }
        if (tencentKey != null && !tencentKey.isEmpty()) {
            String addr = tencent(tencentKey, lat, lng, fallback);
            return Map.of("address", addr, "provider", "tencent");
        }
        return Map.of("address", fallback, "provider", "none");
    }

    private String amap(String key, double lng, double lat, String fallback) {
        String url = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/geocode/regeo")
                .queryParam("key", key)
                .queryParam("location", lng + "," + lat)
                .queryParam("extensions", "base")
                .build().encode().toUriString();
        try {
            Map<String, Object> body = MAPPER.readValue(
                    restTemplate.getForEntity(url, String.class).getBody(),
                    new TypeReference<Map<String, Object>>() {
                    });
            if ("1".equals(String.valueOf(body.get("status"))) && body.get("regeocode") instanceof Map<?, ?> re) {
                Object addr = ((Map<?, ?>) re).get("formatted_address");
                if (addr != null && !String.valueOf(addr).isEmpty()) {
                    return String.valueOf(addr);
                }
            }
        } catch (Exception e) {
            log.warn("高德逆地址解析失败，已回退坐标: {}", e.getMessage());
        }
        return fallback;
    }

    private String tencent(String key, double lat, double lng, String fallback) {
        String url = UriComponentsBuilder.fromHttpUrl("https://apis.map.qq.com/ws/geocoder/v1/")
                .queryParam("location", lat + "," + lng)
                .queryParam("key", key)
                .build().encode().toUriString();
        try {
            Map<String, Object> body = MAPPER.readValue(
                    restTemplate.getForEntity(url, String.class).getBody(),
                    new TypeReference<Map<String, Object>>() {
                    });
            if (String.valueOf(body.get("status")).equals("0") && body.get("result") instanceof Map<?, ?> result) {
                Object recommend = null;
                if (((Map<?, ?>) result).get("formatted_addresses") instanceof Map<?, ?> fa) {
                    recommend = fa.get("recommend");
                }
                if (recommend != null && !String.valueOf(recommend).isEmpty()) {
                    return String.valueOf(recommend);
                }
                Object address = ((Map<?, ?>) result).get("address");
                if (address != null && !String.valueOf(address).isEmpty()) {
                    return String.valueOf(address);
                }
            }
        } catch (Exception e) {
            log.warn("腾讯逆地址解析失败，已回退坐标: {}", e.getMessage());
        }
        return fallback;
    }
}
