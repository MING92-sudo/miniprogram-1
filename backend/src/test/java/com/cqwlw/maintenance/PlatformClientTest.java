package com.cqwlw.maintenance;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.config.PlatformProperties;
import com.cqwlw.maintenance.service.PlatformClient;
import com.cqwlw.maintenance.service.PlatformTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpStatus;

/** 查询 401 重登重试 1 次；监管写请求 401 不重放。 */
class PlatformClientTest {

    private PlatformClient client;
    private PlatformTokenService tokenService;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        PlatformProperties props = new PlatformProperties();
        props.setAuthLoginUrl("https://auth.example.com/login");
        props.setApiBaseUrl("https://api.example.com");
        props.setUsername("u");
        props.setKey("k");
        props.setAppcode("a");
        props.setSecret("s");
        tokenService = new PlatformTokenService(props, restTemplate);
        client = new PlatformClient(props, restTemplate, tokenService);
    }

    private void mockLogin(String token) {
        mockLogin(ExpectedCount.once(), token);
    }

    private void mockLogin(ExpectedCount count, String token) {
        server.expect(count, requestTo(org.hamcrest.Matchers.startsWith("https://auth.example.com/login")))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"ok\",\"data\":{\"access_token\":\"" + token
                                + "\",\"expires_in\":3599}}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void queryEntityIdParsesDataArrayAndSendsForm() {
        mockLogin("tk1");
        server.expect(requestTo("https://api.example.com/entity/queryID"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tk1"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("organizationCode=91500106MAABU3795M")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("unitName=")))
                .andRespond(withSuccess(
                        "{\"code\":\"200\",\"message\":\"ok\",\"data\":[{\"entityID\":\"311715940563360\"}]}",
                        MediaType.APPLICATION_JSON));
        assertEquals("311715940563360", client.queryEntityId("91500106MAABU3795M", "重庆博威电梯有限公司"));
        server.verify();
    }

    @Test
    void query401RefreshesOnce() {
        mockLogin("expired");
        server.expect(requestTo("https://api.example.com/entity/queryWorkList"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        mockLogin("fresh");
        server.expect(requestTo("https://api.example.com/entity/queryWorkList"))
                .andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"ok\",\"data\":[{\"platform_id\":\"worker-1\"}]}",
                        MediaType.APPLICATION_JSON));
        List<Map<String, Object>> workers = client.queryWorkList("0", null);
        assertEquals("worker-1", workers.get(0).get("platform_id"));
        server.verify();
    }

    @Test
    void maintenanceUpload401IsNotRetried() {
        mockLogin("expired");
        server.expect(requestTo("https://api.example.com/elevator/maintenanceRecord"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        mockLogin(ExpectedCount.between(0, 1), "fresh");
        server.expect(ExpectedCount.between(0, 1),
                        requestTo("https://api.example.com/elevator/maintenanceRecord"))
                .andRespond(withSuccess("{\"code\":200,\"message\":\"ok\"}", MediaType.APPLICATION_JSON));

        BizException e = assertThrows(BizException.class,
                () -> client.uploadMaintenanceRecord(Map.of("elevatorCode", "212520")));

        assertEquals(2003, e.getCode());
        server.verify();
    }

    @Test
    void legacyAndWorkerWritesAreNotRetried() {
        mockLogin("legacy-expired");
        server.expect(requestTo("https://api.example.com/record/uploadMaintainRecord"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        mockLogin("worker-expired");

        RestTemplate uploadRestTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "uploadRestTemplate");
        MockRestServiceServer uploadServer = MockRestServiceServer.bindTo(uploadRestTemplate).build();
        uploadServer.expect(requestTo("https://api.example.com/entity/updateWorkState"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        BizException legacy = assertThrows(BizException.class,
                () -> client.uploadLegacyRecord(Map.of("originalRecordId", "1948179075784507801"),
                        "主维保", "配合维保"));
        assertEquals(2003, legacy.getCode());

        BizException worker = assertThrows(BizException.class,
                () -> client.registerWorkerState(Map.of("workManName", "主维保"),
                        new byte[]{1}, "certificate.pdf"));

        assertEquals(2003, worker.getCode());
        uploadServer.verify();
        server.verify();
    }

    @Test
    void non200BusinessCodeThrows2002() {
        mockLogin("tk2");
        server.expect(requestTo("https://api.example.com/entity/queryID"))
                .andRespond(withSuccess("{\"code\":404,\"message\":\"未查到\",\"data\":null}",
                        MediaType.APPLICATION_JSON));
        BizException e = assertThrows(BizException.class, () -> client.queryEntityId("org", "unit"));
        assertEquals(2002, e.getCode());
    }

    @Test
    void elevatorInfoSendsOnlyNonEmptyConditions() {
        mockLogin("tk3");
        server.expect(requestTo("https://api.example.com/equipment/queryElevatorInfo"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("factoryNumber=SGL-1")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("deviceCode"))))
                .andRespond(withSuccess(
                        "{\"code\":200,\"message\":\"ok\",\"data\":[{\"elevatorCode\":\"130421\"}]}",
                        MediaType.APPLICATION_JSON));
        Map<String, String> cond = new LinkedHashMap<>();
        cond.put("factoryNumber", "SGL-1");
        cond.put("deviceCode", "");
        List<Map<String, Object>> list = client.queryElevatorInfo(cond);
        assertEquals("130421", list.get(0).get("elevatorCode"));
    }
}
