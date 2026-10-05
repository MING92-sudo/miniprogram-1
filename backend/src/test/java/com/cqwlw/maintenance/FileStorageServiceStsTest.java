package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.service.FileStorageService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P4 直传元数据（docs/04 A.6 POST /files/sts）：模式探测 + 上传目录消毒（真实 STS 签发放置云端 CAM 部署） */
class FileStorageServiceStsTest {

    private FileStorageService service(AppProperties props) {
        return new FileStorageService(props, null, null, null);
    }

    @Test
    void localModeWhenCosNotConfigured() {
        Map<String, Object> m = service(new AppProperties()).stsDirective("", 1800, "http://localhost:8080");
        assertEquals("LOCAL", m.get("mode"));
        assertEquals("http://localhost:8080/files/upload", m.get("uploadUrl"));
        assertTrue(((String) m.get("dir")).matches("checkin/\\d{8}/"));
    }

    @Test
    void cosManagedModeReturnsAuthUrl() {
        AppProperties p = new AppProperties();
        p.setCosBucket("7072-prod-d3gg6nba2f3160af9-1498557567");
        p.setCosRegion("ap-shanghai");
        // Minor3：Java 侧不再带默认值——yml 绑定注入（此处显式模拟 yml 默认）
        p.setCosAuthUrl("http://api.weixin.qq.com/_/cos/getauth");
        Map<String, Object> m = service(p).stsDirective("checkin/20260928/", 1800, "http://x");
        assertEquals("COS", m.get("mode"));
        assertEquals("7072-prod-d3gg6nba2f3160af9-1498557567", m.get("bucket"));
        assertEquals("ap-shanghai", m.get("region"));
        assertEquals("http://api.weixin.qq.com/_/cos/getauth", m.get("authUrl"));
        assertEquals("7072-prod-d3gg6nba2f3160af9-1498557567/checkin/20260928/", m.get("allowPrefix"));
    }

    @Test
    void cosStaticModeHasNoAuthUrlAndNotesStsDeployment() {
        AppProperties p = new AppProperties();
        p.setCosBucket("b");
        p.setCosRegion("ap-shanghai");
        p.setCosSecretId("AKID");
        p.setCosSecretKey("secret");
        Map<String, Object> m = service(p).stsDirective("checkin/", 3600, "http://x");
        assertEquals("COS", m.get("mode"));
        assertEquals("", m.get("authUrl"));
        assertTrue(String.valueOf(m.get("stsNote")).contains("自建"));
    }

    @Test
    void sanitizeDirRejectsTraversal() {
        assertEquals("checkin/", FileStorageService.sanitizeDir("../etc/passwd"));
        assertEquals("checkin/", FileStorageService.sanitizeDir(".."));
        assertEquals("checkin/", FileStorageService.sanitizeDir("/../x"));
    }

    @Test
    void sanitizeDirNormalizes() {
        assertEquals("CheckIn/2026/", FileStorageService.sanitizeDir("\\CheckIn\\2026"));
        assertEquals("ab/c/", FileStorageService.sanitizeDir("a b/c"));
        assertEquals("checkin/20260928/", FileStorageService.sanitizeDir("checkin/20260928"));
    }

    @Test
    void maxAgeIsCapped() {
        Map<String, Object> low = service(new AppProperties()).stsDirective("", 10, "http://x");
        assertEquals(60, low.get("maxAge"));
        Map<String, Object> high = service(new AppProperties()).stsDirective("", 999999, "http://x");
        assertEquals(7200, high.get("maxAge"));
    }
}
