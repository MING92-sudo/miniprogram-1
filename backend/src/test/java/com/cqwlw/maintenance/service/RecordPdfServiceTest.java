package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.mapper.AppFileMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.lowagie.text.pdf.PdfReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RecordPdfServiceTest {

    private RecordPdfService service;
    private FileStorageService fileStorage;

    /** 1x1 透明 PNG（测试用最小图片） */
    private static final byte[] TINY_PNG = new byte[]{-119, 80, 78, 71, 13, 10, 26, 10};

    @BeforeEach
    void setUp() {
        com.cqwlw.maintenance.entity.Company company = new com.cqwlw.maintenance.entity.Company();
        company.name = "重庆博威电梯有限公司"; // 2.2 平台回填后的本单位名称
        com.cqwlw.maintenance.mapper.CompanyMapper companyMapper =
                mock(com.cqwlw.maintenance.mapper.CompanyMapper.class);
        org.mockito.Mockito.when(companyMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(company));
        fileStorage = mock(FileStorageService.class);
        service = new RecordPdfService(new RestTemplate(), mock(AppFileMapper.class),
                fileStorage, companyMapper) {
            @Override
            protected byte[] loadImage(String url) {
                return url == null || url.isBlank() ? null : TINY_PNG.clone();
            }
        };
    }

    private AppProperties cosProperties() {
        AppProperties props = new AppProperties();
        props.setCosBucket("bucket-1250000000");
        props.setCosRegion("ap-shanghai");
        return props;
    }

    private RecordPdfService imageService(RestTemplate restTemplate) {
        RecordPdfService pdf = new RecordPdfService(new RestTemplate(), mock(AppFileMapper.class),
                fileStorage, mock(com.cqwlw.maintenance.mapper.CompanyMapper.class));
        ReflectionTestUtils.setField(pdf, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(pdf, "props", cosProperties());
        return pdf;
    }

    @Test
    void privateAndMetadataAddressesNeverFetched() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RecordPdfService pdf = imageService(restTemplate);
        List<String> urls = List.of(
                "https://localhost/health",
                "https://127.0.0.1:8080/health",
                "https://10.0.0.8/internal",
                "https://172.16.0.8/internal",
                "https://192.168.1.8/internal",
                "https://169.254.169.254/latest/meta-data",
                "https://[::1]/health");

        for (String url : urls) {
            assertNull(pdf.loadImage(url), url);
        }

        verifyNoMoreInteractions(restTemplate);
    }

    @Test
    void configuredCosImageStillLoads() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RecordPdfService pdf = imageService(restTemplate);
        String cosUrl = "https://bucket-1250000000.cos.ap-shanghai.myqcloud.com/sign.png?s=secret";
        when(restTemplate.getForEntity(cosUrl, byte[].class)).thenReturn(ResponseEntity.ok(TINY_PNG));

        assertArrayEquals(TINY_PNG, pdf.loadImage(cosUrl));
        assertNull(pdf.loadImage("https://other.cos.ap-shanghai.myqcloud.com/sign.png"));
        assertNull(pdf.loadImage("http://bucket-1250000000.cos.ap-shanghai.myqcloud.com/sign.png"));

        verify(restTemplate).getForEntity(cosUrl, byte[].class);
        verifyNoMoreInteractions(restTemplate);
    }

    @Test
    void redirectToPrivateAddressIsNotFollowed() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        RecordPdfService pdf = imageService(restTemplate);
        String cosUrl = "https://bucket-1250000000.cos.ap-shanghai.myqcloud.com/sign.png";
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, "http://169.254.169.254/latest/meta-data");
        when(restTemplate.getForEntity(cosUrl, byte[].class))
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND).headers(headers).build());

        assertNull(pdf.loadImage(cosUrl));

        verify(restTemplate).getForEntity(cosUrl, byte[].class);
        verifyNoMoreInteractions(restTemplate);
    }

    @Test
    void simpleClientFactoryDisablesRedirectBeforeNetworkAccess() throws Exception {
        RecordPdfService pdf = new RecordPdfService(new RestTemplate(), mock(AppFileMapper.class),
                fileStorage, mock(com.cqwlw.maintenance.mapper.CompanyMapper.class));
        RestTemplate secured = (RestTemplate) ReflectionTestUtils.getField(pdf, "restTemplate");
        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory) secured.getRequestFactory();

        ClientHttpRequest request = factory.createRequest(
                URI.create("https://bucket-1250000000.cos.ap-shanghai.myqcloud.com/sign.png"), HttpMethod.GET);
        HttpURLConnection connection = (HttpURLConnection) ReflectionTestUtils.getField(request, "connection");
        try {
            assertFalse(connection.getInstanceFollowRedirects());
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void unsupportedFactoryFailsClosedBeforeRemoteFetch() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getRequestFactory()).thenReturn(mock(ClientHttpRequestFactory.class));
        RecordPdfService pdf = new RecordPdfService(restTemplate, mock(AppFileMapper.class),
                fileStorage, mock(com.cqwlw.maintenance.mapper.CompanyMapper.class));
        ReflectionTestUtils.setField(pdf, "props", cosProperties());
        org.mockito.Mockito.clearInvocations(restTemplate);

        assertNull(pdf.loadImage("https://bucket-1250000000.cos.ap-shanghai.myqcloud.com/sign.png"));

        verifyNoMoreInteractions(restTemplate);
    }

    private UseUnit uu(String unitName) {
        UseUnit u = new UseUnit();
        u.unitName = unitName;
        return u;
    }

    private MaintainRecord record() {
        MaintainRecord r = new MaintainRecord();
        r.id = "ur_1";
        r.originalRecordId = "1948179075784507801";
        r.elevatorName = "龙湖花园3栋 1号梯";
        r.elevatorCode = "212520";
        r.workType = "半月维保";
        r.workTypeCode = "HM";
        r.workerName = "张伟";
        r.assistantName = "李强";
        r.checkinTime = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        r.checkoutTime = LocalDateTime.of(2026, 10, 1, 11, 0, 0);
        r.duration = "02:00:00";
        r.reportStatus = "REPORTED";
        r.itemsJson = JsonUtil.write(List.of(
                Map.of("name", "层门锁紧元件啮合长度", "requirement", "不小于 7mm",
                        "freq", "HALF", "result", "NORMAL"),
                Map.of("name", "曳引机运行状态", "requirement", "运行时无异常振动和异常声响",
                        "freq", "HALF", "result", "ABNORMAL",
                        "abnormalDesc", "异响", "problemCode", "S2")));
        r.problemCodesJson = JsonUtil.write(List.of("S2"));
        r.photosJson = JsonUtil.write(List.of(
                "https://bucket.cos.example/photo1.jpg", "/files/file_1"));
        r.workerSignatureUrl = "https://bucket.cos.example/sign1.png";
        r.assistantSignatureUrl = "";
        r.signatureUrl = "https://bucket.cos.example/sign2.png";
        r.previewContextJson = JsonUtil.write(Map.of("companyName", "重庆渝达物业管理有限公司"));
        return r;
    }

    /** 年度维保累加式清单：76 条（附件 A 半月31+季度13+半年15+年度17） */
    private MaintainRecord annualRecord() {
        MaintainRecord r = record();
        r.workType = "年度维保";
        r.workTypeCode = "OY";
        List<Map<String, Object>> items = new ArrayList<>();
        String[] freqs = {"HALF", "QUARTER", "HALF_YEAR", "YEAR"};
        int[] counts = {31, 13, 15, 17};
        for (int g = 0; g < freqs.length; g++) {
            for (int i = 1; i <= counts[g]; i++) {
                items.add(Map.of("name", "年度项目 " + g + "-" + i,
                        "requirement", "符合标准值", "freq", freqs[g], "result", "NORMAL"));
            }
        }
        r.itemsJson = JsonUtil.write(items);
        return r;
    }

    private Map<String, Object> faultView() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", "f_1");
        v.put("faultNo", "BWJX202610011030001");
        v.put("status", "CLOSED");
        v.put("createdAt", "2026-10-01 10:30:00");
        v.put("createdBy", "emp_1");
        v.put("createdByName", "张伟");
        v.put("reporterPhone", "13800000000");
        v.put("faultType", "困人（紧急）");
        v.put("faultReason", "门锁触点氧化，平层感应器偏移");
        v.put("trappedCount", 2);
        v.put("floor", "5-6 层之间");
        v.put("rescuedAt", "2026-10-01 11:05:00");
        v.put("proxySource", "PHONE");
        v.put("dispatchedAt", "2026-10-01 10:32:00");
        v.put("dispatchedWorkerName", "张伟");
        v.put("arrivedAt", "2026-10-01 10:55:00");
        v.put("finishedAt", "2026-10-01 12:10:00");
        v.put("desc", "轿厢停在 5 层与 6 层之间，门无法开启");
        v.put("siteDesc", "门锁触点氧化，平层感应器偏移");
        v.put("result", "清洁门锁触点并调整平层感应器，更换门锁触点 1 只");
        v.put("todoDesc", "");
        v.put("photos", List.of("https://bucket.cos.example/f1.jpg"));
        v.put("repairPhotos", List.of("https://bucket.cos.example/r1.jpg"));
        v.put("workerSignature", "https://bucket.cos.example/worker-sign.png");
        v.put("signature", "https://bucket.cos.example/sign.png");
        v.put("confirmedAt", "2026-10-01 14:00:00");
        v.put("useUnitName", "重庆渝达物业管理有限公司");
        v.put("insideNumber", "KT-01");
        v.put("location", "渝北区龙山一路 88 号世纪大厦");
        v.put("projectName", "世纪大厦");
        v.put("category", "曳引与强制驱动电梯");
        v.put("factoryNumber", "OT-2021-0088");
        v.put("deviceCode", "DT-CQ-2024-001");
        v.put("regCode", "TSCQ5001120001");
        v.put("model", "OTIS 300VF");
        v.put("manufacturer", "奥的斯电梯（中国）有限公司");
        v.put("driveMode", "曳引驱动");
        v.put("ratedLoadSpec", "1000 kg");
        v.put("ratedSpeedSpec", "1.75 m/s");
        v.put("stationsDoors", "18 层 18 站 18 门");
        v.put("companyName", "重庆博威电梯有限公司");
        return v;
    }

    @Test
    void pdfStartsWithHeaderAndContainsContent() {        byte[] pdf = service.render(record(), null, null);
        assertTrue(pdf.length > 2000);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void deviceRowUsesSnapshotUnitName() {
        assertEquals("启元物业（快照）",
                RecordPdfService.unitNameForSnapshot("启元物业（快照）", uu("改名后物业")));
    }

    @Test
    void nullSnapshotShowsPendingLabel() {
        assertEquals("归属待核对", RecordPdfService.unitNameForSnapshot(null, uu("改名后物业")));
        assertEquals("归属待核对", RecordPdfService.unitNameForSnapshot("", uu("改名后物业")));
        assertEquals("归属待核对", RecordPdfService.unitNameForSnapshot("   ", uu("改名后物业")));
    }

    @Test
    void renderWithSnapshotStillProducesPdf() {
        MaintainRecord r = record();
        r.useUnitName = "启元物业（快照）";
        byte[] pdf = service.render(r, null, uu("改名后物业"));
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

    /** 必须兜底为空 Map 并正常出 PDF。 */
    @Test
    void literalNullSafetyJsonStillRenders() {
        MaintainRecord r = record();
        r.safetyJson = "null";
        byte[] pdf = service.render(r, null, null);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));

        r.safetyJson = "{\"twoPerson\":true,\"powerOff\":\"true\"}";
        byte[] pdf2 = service.render(r, null, null);
        assertEquals("%PDF", new String(pdf2, 0, 4, StandardCharsets.US_ASCII));
    }

    /** 年度维保 76 项必须跨页：验证分页与每页表头重复不抛错 */
    @Test
    void annualRecordSpansMultiplePages() throws Exception {
        byte[] pdf = service.render(annualRecord(), null, null);
        try (PdfReader reader = new PdfReader(pdf)) {
            assertTrue(reader.getNumberOfPages() >= 2);
        }
    }

    @Test
    void appendixAndTableRangeLabels() {
        assertEquals("A", RecordPdfService.appendixOf("曳引与强制驱动电梯"));
        assertEquals("B", RecordPdfService.appendixOf("液压驱动电梯"));
        assertEquals("C", RecordPdfService.appendixOf("杂物电梯"));
        assertEquals("D", RecordPdfService.appendixOf("自动扶梯与自动人行道"));
        assertEquals("A", RecordPdfService.appendixOf(null));
        assertEquals("附件D 表D-1（半月）", RecordPdfService.rangeLabel("D", "HM"));
        assertEquals("附件A 表A-1～表A-2（半月、季度）", RecordPdfService.rangeLabel("A", "TM"));
        assertEquals("附件B 表B-1～表B-4（半月、季度、半年、年度）",
                RecordPdfService.rangeLabel("B", "OY"));
        assertTrue(RecordPdfService.rangeLabel("A", "FM").startsWith("按需维保"));
    }

    /** 历史记录缺 workTypeCode：按名称推断类别码，否则副标题表号会错 */
    @Test
    void workTypeCodeFallsBackToName() {
        MaintainRecord r = new MaintainRecord();
        r.workType = "季度维保";
        assertEquals("TM", RecordPdfService.workTypeCodeOf(r));
        r.workTypeCode = "OY";
        assertEquals("OY", RecordPdfService.workTypeCodeOf(r));
    }

    /** 急修记录单：未闭环（无到场/无签字/无照片）也必须完整出单 */
    @Test
    void faultPdfRendersWithPlaceholders() {
        Map<String, Object> v = faultView();
        v.put("status", "ASSIGNED");
        v.put("arrivedAt", "");
        v.put("finishedAt", "");
        v.put("signature", "");
        v.put("confirmedAt", "");
        v.put("photos", List.of());
        byte[] pdf = service.renderFault(v);
        assertTrue(pdf.length > 1000);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void recordPhotoUrlsIncludeItemAndRecordPhotos() {
        MaintainRecord r = record();
        r.photosJson = JsonUtil.write(List.of("https://bucket.cos.example/record.jpg"));
        when(fileStorage.resolveStoredUrl("https://bucket.cos.example/record.jpg"))
                .thenReturn("https://bucket.cos.example/record.jpg");
        when(fileStorage.resolveStoredUrl("https://bucket.cos.example/item.jpg"))
                .thenReturn("https://bucket.cos.example/item.jpg");

        List<String> urls = service.recordPhotoUrls(r,
                List.of(Map.of("photos", List.of("https://bucket.cos.example/item.jpg"))));

        assertTrue(urls.contains("https://bucket.cos.example/item.jpg"));
        assertTrue(urls.contains("https://bucket.cos.example/record.jpg"));
    }

    @Test
    void faultDeviceRowsUseFactoryNumberAndOmitTechnicalFields() {
        List<String[]> rows = service.deviceRowsOfMap(faultView());
        StringBuilder flat = new StringBuilder();
        for (String[] row : rows) {
            for (String cell : row) {
                flat.append(cell).append('|');
            }
        }
        String text = flat.toString();
        assertTrue(text.contains("产品编号"));
        assertTrue(text.contains("OT-2021-0088"));
        assertFalse(text.contains("制造单位"));
        assertFalse(text.contains("安装/改造/重大修理单位"));
        assertFalse(text.contains("驱动方式"));
        assertFalse(text.contains("额定载重量"));
        assertFalse(text.contains("额定速度"));
        assertFalse(text.contains("电梯型号"));
    }

    @Test
    void faultStatusTextCoversSevenStates() {
        assertEquals("待接单", RecordPdfService.faultStatusText("OPEN"));
        assertEquals("已接单", RecordPdfService.faultStatusText("ASSIGNED"));
        assertEquals("处置中", RecordPdfService.faultStatusText("PROCESSING"));
        assertEquals("待使用单位确认", RecordPdfService.faultStatusText("PENDING_CONFIRM"));
        assertEquals("待整改", RecordPdfService.faultStatusText("REFUSED"));
        assertEquals("已闭环", RecordPdfService.faultStatusText("CLOSED"));
        assertEquals("已取消", RecordPdfService.faultStatusText("CANCELLED"));
    }

    @Test
    void faultSourceTextDistinguishesProxyAndPortal() {
        assertEquals("电话代报修", RecordPdfService.faultSourceText(Map.<String, Object>of("proxySource", "PHONE")));
        assertEquals("微信代报修", RecordPdfService.faultSourceText(Map.<String, Object>of("proxySource", "WECHAT")));
        assertEquals("现场代报修", RecordPdfService.faultSourceText(Map.<String, Object>of("proxySource", "ONSITE")));
        assertEquals("使用单位报修", RecordPdfService.faultSourceText(Map.<String, Object>of("createdBy", "unit:uu_1")));
        assertEquals("小程序报修", RecordPdfService.faultSourceText(Map.<String, Object>of("createdBy", "emp_1")));
    }

    /** 签字回退：记录未落签字 URL 时按来源工单的签字 fileId 解析（与签退 resolveSignatureUrl 同口径，仅内存回填）； */
    @Test
    void backfillsSignaturesFromSourceWorkOrder() throws Exception {
        org.mockito.Mockito.when(fileStorage.urlOf("t_file_sig_w")).thenReturn("/files/t_file_sig_w");
        org.mockito.Mockito.when(fileStorage.urlOf("t_file_sig_a")).thenReturn("/files/t_file_sig_a");
        com.cqwlw.maintenance.mapper.WorkOrderMapper woMapper =
                mock(com.cqwlw.maintenance.mapper.WorkOrderMapper.class);
        com.cqwlw.maintenance.entity.WorkOrder o = new com.cqwlw.maintenance.entity.WorkOrder();
        o.id = "t_wo_done";
        o.workerSignatureFileId = "t_file_sig_w";
        o.assistantSignatureFileId = "t_file_sig_a";
        org.mockito.Mockito.when(woMapper.selectById("t_wo_done")).thenReturn(o);
        java.lang.reflect.Field fld = RecordPdfService.class.getDeclaredField("workOrderMapper");
        fld.setAccessible(true);
        fld.set(service, woMapper);

        MaintainRecord r = record();
        r.workOrderId = "t_wo_done";
        r.workerSignatureUrl = "";
        r.assistantSignatureUrl = null;
        byte[] pdf = service.render(r, null, uu("演示单位"));
        assertTrue(pdf.length > 1000);
        assertEquals("/files/t_file_sig_w", r.workerSignatureUrl);
        assertEquals("/files/t_file_sig_a", r.assistantSignatureUrl);

        // 已有签字 URL 的记录不被工单覆盖
        MaintainRecord r2 = record();
        r2.workOrderId = "t_wo_done";
        r2.workerSignatureUrl = "/files/existing_w";
        r2.assistantSignatureUrl = "/files/existing_a";
        service.render(r2, null, uu("演示单位"));
        assertEquals("/files/existing_w", r2.workerSignatureUrl);
        assertEquals("/files/existing_a", r2.assistantSignatureUrl);

        // 无来源工单（workOrderId 空）静默跳过
        MaintainRecord r3 = record();
        r3.workerSignatureUrl = "";
        r3.assistantSignatureUrl = "";
        service.render(r3, null, uu("演示单位"));
        assertEquals("", r3.workerSignatureUrl);
    }

    /** 版面核对样本：写入 target/ 供渲染肉眼检查（不是断言） */
    @Test
    void dumpSamplePdfForLayoutReview() throws Exception {
        java.nio.file.Files.write(
                java.nio.file.Path.of("target", "sample-record.pdf"),
                service.render(annualRecord(), null, null));
        // 附件 D（自动扶梯）样本：核对品种追加技术参数行与表 D-x 副标题
        com.cqwlw.maintenance.entity.Elevator escalator = new com.cqwlw.maintenance.entity.Elevator();
        escalator.category = "自动扶梯与自动人行道";
        escalator.regCode = "TSCQ5001120099";
        escalator.deviceCode = "DT-CQ-2024-099";
        escalator.insideNumber = "FT-01";
        escalator.projectName = "世纪大厦";
        escalator.location = "渝北区龙山一路 88 号世纪大厦";
        escalator.model = "迅达 9300";
        escalator.manufacturer = "迅达（中国）电梯有限公司";
        escalator.driveMode = "链条驱动";
        com.cqwlw.maintenance.entity.UseUnit uu = new com.cqwlw.maintenance.entity.UseUnit();
        uu.unitName = "重庆渝达物业管理有限公司";
        MaintainRecord halfYear = record();
        halfYear.workType = "半年维保";
        halfYear.workTypeCode = "SM";
        java.nio.file.Files.write(
                java.nio.file.Path.of("target", "sample-record-d.pdf"),
                service.render(halfYear, escalator, uu));
        java.nio.file.Files.write(
                java.nio.file.Path.of("target", "sample-fault.pdf"),
                service.renderFault(faultView()));
    }
}
