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

    /** 手动重报：仅 FAILED 记录允许（保守策略，防止幂等性未知下重复上报） */
    public MaintainRecord reupload(String recordId) {
        MaintainRecord r = recordMapper.selectById(recordId);
        if (r == null) {
            throw new BizException(1404, "维保记录不存在");
        }
        if (!"FAILED".equals(r.reportStatus)) {
            throw new BizException(1003, "仅上报失败的记录可手动重报");
        }
        upload(r, "REUPLOAD");
        return r;
    }

    /** 2.8 存量推送：开关关闭时为 0；对未上报成功的历史记录逐条转发（手动触发，不做自动重试） */
    public int syncLegacy() {
        if (!props.isLegacyUploadEnabled() || !configured()) {
            return 0;
        }
        List<MaintainRecord> pending = recordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                .ne(MaintainRecord::getReportStatus, "REPORTED")
                .isNotNull(MaintainRecord::getReportPayloadJson));
        int n = 0;
        for (MaintainRecord r : pending) {
            try {
                if ("REPORTED".equals(upload(r, "LEGACY"))) {
                    n++;
                }
            } catch (Exception e) {
                log.warn("2.8 存量上报失败（不自动重试）: originalRecordId={}, {}", r.originalRecordId, e.getMessage());
            }
        }
        return n;
    }

    private String upload(MaintainRecord r, String action) {
        Map<String, Object> payload = JsonUtil.readMap(r.reportPayloadJson);
        if (payload == null || payload.isEmpty()) {
            throw new BizException(422, "签退报文快照缺失，无法上报");
        }
        Map<String, Object> body = platformClient.uploadMaintenanceRecord(payload);
        r.reportStatus = "REPORTED";
        r.retryCount = (r.retryCount == null ? 0 : r.retryCount) + 1;
        recordMapper.updateById(r);
        log(r, action, "200", str(body.get("message")), "SUCCESS", digest(payload));
        return "REPORTED";
    }

    /** 失败路径由 attemptUpload 捕获调用：记录 FAILED 日志并更新记录状态 */
    void markFailed(MaintainRecord r, String action, Exception e) {
        r.reportStatus = "FAILED";
        r.retryCount = (r.retryCount == null ? 0 : r.retryCount) + 1;
        recordMapper.updateById(r);
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
