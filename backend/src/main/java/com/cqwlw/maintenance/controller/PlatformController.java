package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.service.PlatformClient;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.PlatformSyncService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 平台档案同步与写链路（P3，docs/07 实测口径沉淀）：
 * 2.2/2.7/2.5 拉回落库；2.3/2.4 登记转发（multipart）；2.6 手动重报仅限 FAILED（AGENTS §2.3）。
 */
@RestController
public class PlatformController {

    private final PlatformSyncService platformSyncService;
    private final PlatformReportService reportService;
    private final PlatformClient platformClient;

    public PlatformController(PlatformSyncService platformSyncService,
                              PlatformReportService reportService, PlatformClient platformClient) {
        this.platformSyncService = platformSyncService;
        this.reportService = reportService;
        this.platformClient = platformClient;
    }

    @PostMapping("/platform/sync")
    public ApiResponse<Map<String, Object>> sync() {
        return ApiResponse.ok(platformSyncService.syncAll());
    }

    /** 手动重报平台 2.6（仅 reportStatus=FAILED；不自动重试红线不变，AGENTS §2.3） */
    @PostMapping("/platform/records/{id}/reupload")
    public ApiResponse<Object> reupload(@PathVariable String id) {
        MaintainRecord r = reportService.reupload(id);
        return ApiResponse.ok(Map.of(
                "ok", true,
                "id", r.id,
                "reportStatus", r.reportStatus == null ? "" : r.reportStatus));
    }

    /** 2.3 建立维保服务关系（multipart + contractFile，参数名按实测 useUnitName，docs/07） */
    @PostMapping("/platform/register/service")
    public ApiResponse<Object> registerService(
            @RequestParam String useUnitName,
            @RequestParam String useUnitEntityID,
            @RequestParam(defaultValue = "0") String changState,
            @RequestParam String serviceStartDate,
            @RequestParam String serviceEndDate,
            @RequestPart(value = "contractFile", required = false) MultipartFile contractFile) {
        requireFile(contractFile, "contractFile");
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("useUnitName", useUnitName);
        fields.put("useUnitEntityID", useUnitEntityID);
        fields.put("changState", changState);
        fields.put("serviceStartDate", serviceStartDate);
        fields.put("serviceEndDate", serviceEndDate);
        Map<String, Object> resp = platformClient.registerServiceState(fields,
                bytes(contractFile), filename(contractFile));
        return ApiResponse.ok(resp.get("data") == null ? Map.of("ok", true) : resp.get("data"));
    }

    /** 2.4 登记维保人员（multipart + certificateFile，docs/07 实测口径） */
    @PostMapping("/platform/register/worker")
    public ApiResponse<Object> registerWorker(
            @RequestParam String workManName,
            @RequestParam String workManCertificate,
            @RequestParam String workStartDate,
            @RequestParam String workEndDate,
            @RequestParam String workManPhone,
            @RequestParam(defaultValue = "0") String changState,
            @RequestPart(value = "certificateFile", required = false) MultipartFile certificateFile) {
        requireFile(certificateFile, "certificateFile");
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("workManName", workManName);
        fields.put("workManCertificate", workManCertificate);
        fields.put("workStartDate", workStartDate);
        fields.put("workEndDate", workEndDate);
        fields.put("workManPhone", workManPhone);
        fields.put("changState", changState);
        Map<String, Object> resp = platformClient.registerWorkerState(fields,
                bytes(certificateFile), filename(certificateFile));
        return ApiResponse.ok(resp.get("data") == null ? Map.of("ok", true) : resp.get("data"));
    }

    private static void requireFile(MultipartFile file, String name) {
        if (file == null || file.isEmpty()) {
            throw new BizException(422, "缺少必填文件: " + name);
        }
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new BizException(422, "文件读取失败: " + file.getOriginalFilename());
        }
    }

    private static String filename(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isEmpty() ? "file.pdf" : name;
    }
}
