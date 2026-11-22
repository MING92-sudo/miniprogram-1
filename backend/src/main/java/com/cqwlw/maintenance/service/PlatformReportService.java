package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.config.PlatformProperties;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.RegUploadLog;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.RegUploadLogMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台上报服务（P3，docs/07-full-test 实测口径沉淀）：
 * 签退后自动转发 2.6（失败不自动重试，AGENTS §2.3 红线），
 * FAILED 记录允许手动重报；2.8 存量推送仅在 legacy-upload-enabled 开关打开时执行。
 * 上报日志 request_digest 脱敏：手机号打码、不含 token（AGENTS §2.4）。
 */
@Service
public class PlatformReportService {

    private static final Logger log = LoggerFactory.getLogger(PlatformReportService.class);

    private final MaintainRecordMapper recordMapper;
    private final RegUploadLogMapper logMapper;
    private final PlatformClient platformClient;
    private final PlatformTokenService tokenService;
    private final PlatformProperties props;

    public PlatformReportService(MaintainRecordMapper recordMapper, RegUploadLogMapper logMapper,
                                 PlatformClient platformClient, PlatformTokenService tokenService,
                                 PlatformProperties props) {
        this.recordMapper = recordMapper;
        this.logMapper = logMapper;
        this.platformClient = platformClient;
        this.tokenService = tokenService;
        this.props = props;
    }

    public boolean configured() {
        return tokenService.configured();
    }

    /**
     * 签退后自动上报：平台未配置或转发失败均不抛出（不阻塞签退主流程）。
     * 返回最终 reportStatus：REPORTED / FAILED / SUBMITTED（未配置 = 待上报）。
     */
    public String attemptUpload(MaintainRecord r) {
        if (!configured()) {
            return "SUBMITTED";
        }
        try {
            return upload(r, "UPLOAD");
        } catch (Exception e) {
            log.warn("2.6 自动上报失败（不自动重试）: originalRecordId={}, {}", r.originalRecordId, e.getMessage());
            markFailed(r, "UPLOAD", e);
            return "FAILED";
        }
    }

    /** 手动重报：仅链末端 FAILED 记录允许；先原子抢占，避免并发请求重复写平台。 */
    public MaintainRecord reupload(String recordId) {
        MaintainRecord r = recordMapper.selectById(recordId);
        if (r == null) {
            throw new BizException(1404, "维保记录不存在");
        }
        if (!"FAILED".equals(r.reportStatus)) {
            throw new BizException(1003, "仅上报失败的记录可手动重报");
        }
        if (hasNextVersion(r)) {
            throw new BizException(1003, "记录已有后继版本，禁止重报旧快照");
        }
        if (recordMapper.beginReupload(recordId) != 1) {
            throw new BizException(1003, "该记录正在重报或状态已变化，请刷新后查看");
        }
        r.reportStatus = "REUPLOADING";
        try {
            upload(r, "REUPLOAD");
            return r;
        } catch (RuntimeException e) {
            r.reportStatus = "FAILED";
            recordMapper.completeReupload(recordId, "FAILED");
            throw e;
        }
    }

    /** 2.8 存量推送：仅链末端 SUBMITTED 首次迁移；FAILED 只能逐条人工重报，旧版本禁止同步。 */
    public int syncLegacy() {
        if (!props.isLegacyUploadEnabled() || !configured()) {
            return 0;
        }
        int n = 0;
        String afterId = null;
        while (true) {
            LambdaQueryWrapper<MaintainRecord> query = new LambdaQueryWrapper<MaintainRecord>()
                    .eq(MaintainRecord::getReportStatus, "SUBMITTED")
                    .isNotNull(MaintainRecord::getReportPayloadJson)
                    .orderByAsc(MaintainRecord::getId)
                    .last("LIMIT 100");
            if (afterId != null) {
                query.gt(MaintainRecord::getId, afterId);
            }
            List<MaintainRecord> pending = recordMapper.selectList(query);
            if (pending == null || pending.isEmpty()) {
                break;
            }
            java.util.Set<String> superseded = com.cqwlw.maintenance.util.RecordVersionUtil.supersededIds(
                    recordMapper, pending.stream().map(r -> r.id).toList());
            for (MaintainRecord r : pending) {
                if (!"SUBMITTED".equals(r.reportStatus) || superseded.contains(r.id)
                        || recordMapper.beginLegacyUpload(r.id) != 1) {
                    continue;
                }
                r.reportStatus = "LEGACY_UPLOADING";
                try {
                    if ("REPORTED".equals(uploadLegacy(r))) {
                        n++;
                    }
                } catch (Exception e) {
                    r.reportStatus = "SUBMITTED";
                    recordMapper.completeLegacyUpload(r.id, "SUBMITTED");
                    log.warn("2.8 存量上报失败（不自动重试）: originalRecordId={}, {}", r.originalRecordId, e.getMessage());
                }
            }
            afterId = pending.get(pending.size() - 1).id;
            if (pending.size() < 100) {
                break;
            }
        }
        return n;
    }

    /** 2.8 存量提交（/record/uploadMaintainRecord）：与 2.6 不同接口——传姓名而非 ID；
     * 无配合人员时回退主维保姓名（与 2.6 workMan2Id 必填同口径） */
    private String uploadLegacy(MaintainRecord r) {
        Map<String, Object> payload = JsonUtil.readMap(r.reportPayloadJson);
        if (payload == null || payload.isEmpty()) {
            throw new BizException(422, "签退报文快照缺失，无法上报");
        }
        String name2 = r.assistantName == null || r.assistantName.isBlank() ? r.workerName : r.assistantName;
        Map<String, Object> body = platformClient.uploadLegacyRecord(payload, r.workerName, name2);
        r.reportStatus = "REPORTED";
        r.retryCount = (r.retryCount == null ? 0 : r.retryCount) + 1;
        recordMapper.updateReportProgress(r.id, "REPORTED", r.retryCount);
        recordMapper.completeLegacyUpload(r.id, "REPORTED");
        log(r, "LEGACY", "200", str(body.get("message")), "SUCCESS", digest(payload));
        return "REPORTED";
    }

    private boolean hasNextVersion(MaintainRecord r) {
        Long next = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getPrevVersionId, r.id));
        return next != null && next > 0;
    }

    private String upload(MaintainRecord r, String action) {
        Map<String, Object> payload = JsonUtil.readMap(r.reportPayloadJson);
        if (payload == null || payload.isEmpty()) {
            throw new BizException(422, "签退报文快照缺失，无法上报");
        }
        ReportPayloadValidator.requireComplete(payload);
        Map<String, Object> body = platformClient.uploadMaintenanceRecord(payload);
        r.reportStatus = "REPORTED";
        r.retryCount = (r.retryCount == null ? 0 : r.retryCount) + 1;
        recordMapper.updateReportProgress(r.id, "REPORTED", r.retryCount);
        log(r, action, "200", str(body.get("message")), "SUCCESS", digest(payload));
        return "REPORTED";
    }

    /** 失败路径由 attemptUpload 捕获调用：记录 FAILED 日志并更新记录状态 */
    void markFailed(MaintainRecord r, String action, Exception e) {
        r.reportStatus = "FAILED";
        r.retryCount = (r.retryCount == null ? 0 : r.retryCount) + 1;
        recordMapper.updateReportProgress(r.id, "FAILED", r.retryCount);
        String message = e instanceof BizException ? e.getMessage() : "平台接口调用失败";
        log(r, action, null, message, "FAILED", null);
    }

    private void log(MaintainRecord r, String action, String code, String message,
                     String status, String digest) {
        RegUploadLog entry = new RegUploadLog();
        entry.id = Ids.next("ul");
        entry.originalRecordId = r.originalRecordId;
        entry.action = action;
        entry.requestDigest = digest;
        entry.platformCode = code;
        entry.platformMessage = message == null || message.length() <= 250
                ? message : message.substring(0, 250);
        entry.status = status;
        entry.createdAt = TimeUtil.now();
        logMapper.insert(entry);
    }

    /** 脱敏摘要：手机号打码（11 位与座机），不含 token/密钥；超长截断 */
    public static String digest(Map<String, Object> payload) {
        Map<String, Object> safe = new LinkedHashMap<>();
        payload.forEach((k, v) -> safe.put(k, v instanceof String s ? mask(s) : v));
        String json = JsonUtil.write(safe);
        return json.length() <= 1000 ? json : json.substring(0, 1000);
    }

    private static String mask(String s) {
        return s.replaceAll("(1[3-9]\\d)\\d{4}(\\d{4})", "$1****$2")
                .replaceAll("(\\d{3,4})\\d{7,8}", "$1*******");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
