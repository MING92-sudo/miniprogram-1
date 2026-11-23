package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RequirePerm;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.service.PlatformClient;
import com.cqwlw.maintenance.service.PlatformReportService;
import com.cqwlw.maintenance.service.PlatformSyncService;
import com.cqwlw.maintenance.service.PlatformWorkerIdService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.List;

/** 2.6 手动重报仅限 FAILED（AGENTS §2.3）。 */
@RestController
public class PlatformController {

    private final PlatformSyncService platformSyncService;
    private final PlatformReportService reportService;
    private final PlatformClient platformClient;
    private final com.cqwlw.maintenance.service.AdminArchiveService archiveService;
    private final PlatformWorkerIdService workerIdService;

    public PlatformController(PlatformSyncService platformSyncService,
                              PlatformReportService reportService, PlatformClient platformClient,
                              com.cqwlw.maintenance.service.AdminArchiveService archiveService,
                              PlatformWorkerIdService workerIdService) {
        this.platformSyncService = platformSyncService;
        this.reportService = reportService;
        this.platformClient = platformClient;
        this.archiveService = archiveService;
        this.workerIdService = workerIdService;
    }

    @PostMapping("/platform/sync")
    public ApiResponse<Map<String, Object>> sync() {
        return ApiResponse.ok(platformSyncService.syncAll());
    }

    /** 存量维保人员 platform_id 回填：调 2.5 全量查询，按证书号回填所有本地有证书人员。 */
    @PostMapping("/platform/workers/backfill")
    public ApiResponse<Map<String, Object>> backfillWorkers() {
        return ApiResponse.ok(workerIdService.backfillAllFromPlatform());
    }

    /** 2.5 单位维保人员查询（原始列表，管理端"平台人员"视图用；只读） */
    @GetMapping("/platform/workers")
    @RequirePerm(PermissionMatrix.PLATFORM_WORKER_VIEW)
    public ApiResponse<List<Map<String, Object>>> workers(@RequestParam(defaultValue = "0") String changState) {
        return ApiResponse.ok(platformClient.queryWorkList(changState, null));
    }

    /** 实测平台不支持按 elevatorCode 查询，仅接受 deviceCode / factoryNumber / registrationCode 之一 */
    @PostMapping("/platform/query/elevator")
    public ApiResponse<Object> queryElevator(@RequestBody Map<String, Object> body) {
        if (!platformClient.configured()) {
            return ApiResponse.ok(Map.of("found", false, "reason", "监管平台凭证未配置"));
        }
        Map<String, String> cond = new java.util.LinkedHashMap<>();
        for (String key : List.of("deviceCode", "factoryNumber", "registrationCode")) {
            String v = body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
            if (!v.isEmpty()) {
                cond.put(key, v);
            }
        }
        if (cond.isEmpty()) {
            throw new BizException(422, "至少填写 设备代码/出厂编号/注册代码 之一（平台不支持按电梯编号查询）");
        }
        List<Map<String, Object>> list = platformClient.queryElevatorInfo(cond);
        return ApiResponse.ok(list.isEmpty()
                ? Map.of("found", false, "reason", "平台未查询到该电梯")
                : Map.of("found", true, "elevator", list.get(0)));
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
        // 2.4 建立(changState=0)/中止(changState=1) → 回写本地绑定状态（档案删除门禁与状态展示用）
        archiveService.updateBindStatusByCertificate(workManCertificate,
                "1".equals(changState) ? 1 : 0);
        // 用户需求①：2.4 平台登记成功 → 本地人员档案自动建档（按证书号去重；随机初始密码走账号流程）
        String employeeId = "";
        String account = workManPhone;
        String initialPassword = "";
        try {
            Long dup = archiveService.employeeCertificateExists(workManCertificate);
            if (dup == null || dup == 0) {
                Map<String, Object> created = archiveService.createEmployee(java.util.Map.of(
                        "name", workManName,
                        "phone", workManPhone,
                        "role", "WORKER",
                        "certificate", workManCertificate,
                        "workStartDate", workStartDate,
                        "workEndDate", workEndDate));
                employeeId = String.valueOf(created.get("id"));
                account = String.valueOf(created.get("account"));
                initialPassword = String.valueOf(created.get("initialPassword"));
            }
        } catch (Exception ignored) {
            // 建档失败不影响登记结果（可用同步兜底）
        }
        // 2.4 本身不返回人员ID，必须靠 2.5 查询获得。
        Map<String, Object> platformBackfill = Map.of("status", "SKIPPED");
        if (!"1".equals(changState)) {
            try {
                platformBackfill = workerIdService.backfillWithRetry(workManCertificate);
            } catch (Exception e) {
                platformBackfill = Map.of("status", "ERROR");
                org.slf4j.LoggerFactory.getLogger(PlatformController.class)
                        .warn("2.4 建档后触发平台ID全量回填失败: certificate={}, {}", workManCertificate, e.getMessage());
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("employeeId", employeeId);
        out.put("account", account);
        out.put("platformBackfill", platformBackfill);
        if (!initialPassword.isEmpty()) {
            out.put("initialPassword", initialPassword);
        }
        // 平台原始 data 合并进 out（本地生成的账号/回填统计优先），避免前端丢失建档结果
        Object platformData = resp.get("data");
        if (platformData instanceof Map<?, ?> data) {
            data.forEach((k, v) -> out.putIfAbsent(String.valueOf(k), v));
        }
        return ApiResponse.ok(out);
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
