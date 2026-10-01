package com.cqwlw.maintenance;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.service.FileStorageService;
import com.cqwlw.maintenance.service.RecordPdfService;
import com.cqwlw.maintenance.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 维保记录 PDF 导出：
 * 中文渲染（STSong-Light）、检查项明细、照片/签字占位降级不抛错。
 */
class RecordPdfServiceTest {

    private RecordPdfService service;

    /** 1x1 透明 PNG（测试用最小图片） */
    private static final byte[] TINY_PNG = new byte[]{-119, 80, 78, 71, 13, 10, 26, 10};

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setCosBucket("test-bucket-1250000000");
        props.setCosRegion("ap-shanghai");
        service = new RecordPdfService(new RestTemplate(), mock(AppFileMapper.class),
                mock(FileStorageService.class), props) {
            @Override
            protected byte[] loadImage(String url) {
                return url == null || url.isBlank() ? null : TINY_PNG.clone();
            }
        };
    }

    @Test
    void onlyOwnCosHostIsFetched() {
        // 回归 SSRF：维保记录里的图片 URL 只允许本系统自有 COS 桶域名，
        // 云元数据端点/内网地址一律不得由服务器代抓
        assertTrue(service.allowedHost(
                "https://test-bucket-1250000000.cos.ap-shanghai.myqcloud.com/a.png"));
        assertFalse(service.allowedHost("http://169.254.169.254/latest/meta-data/"));
        assertFalse(service.allowedHost("http://127.0.0.1:8080/health"));
        assertFalse(service.allowedHost("file:///etc/passwd"));
        assertFalse(service.allowedHost("https://evil-bucket.cos.ap-shanghai.myqcloud.com/a.png"));
        assertFalse(service.allowedHost("https://test-bucket-1250000000.cos.ap-shanghai.myqcloud.com.evil.com/a.png"));
        assertFalse(service.allowedHost("not a url"));
    }

    private MaintainRecord record() {
        MaintainRecord r = new MaintainRecord();
        r.id = "ur_1";
        r.originalRecordId = "1948179075784507801";
        r.elevatorName = "龙湖花园3栋 1号梯";
        r.elevatorCode = "212520";
        r.workType = "半月维保";
        r.workerName = "张伟";
        r.assistantName = "李强";
        r.checkinTime = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        r.checkoutTime = LocalDateTime.of(2026, 10, 1, 11, 0, 0);
        r.duration = "02:00:00";
        r.reportStatus = "REPORTED";
        r.itemsJson = JsonUtil.write(List.of(
                Map.of("name", "层门锁紧元件啮合长度", "result", "NORMAL"),
                Map.of("name", "曳引机运行状态", "result", "ABNORMAL",
                        "abnormalDesc", "异响", "problemCode", "S2")));
        r.problemCodesJson = JsonUtil.write(List.of("S2"));
        r.photosJson = JsonUtil.write(List.of(
                "https://bucket.cos.example/photo1.jpg", "/files/file_1"));
        r.workerSignatureUrl = "https://bucket.cos.example/sign1.png";
        r.assistantSignatureUrl = "";
        r.signatureUrl = "https://bucket.cos.example/sign2.png";
        return r;
    }

    @Test
    void pdfStartsWithHeaderAndContainsContent() {
        byte[] pdf = service.render(record(), null, null);
        assertTrue(pdf.length > 2000);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void emptyRecordStillRendersWithPlaceholders() {
        MaintainRecord r = record();
        r.itemsJson = null;
        r.photosJson = null;
        r.workerSignatureUrl = null;
        byte[] pdf = service.render(r, null, null);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }
}
