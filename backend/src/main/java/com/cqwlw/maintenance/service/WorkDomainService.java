package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * M4 作业域：记录归档后修订（v2 + 新哈希，v1 与签名永久保留）+ 代报修。
 * 修订不触发任何上报（P-5 interim，AGENTS §2.4 无自动重试）；仅已归档可发起。
 */
@Service
public class WorkDomainService {

    private final MaintainRecordMapper recordMapper;
    private final FaultMapper faultMapper;
    private final AuditLogService audit;

    public WorkDomainService(MaintainRecordMapper recordMapper, FaultMapper faultMapper, AuditLogService audit) {
        this.recordMapper = recordMapper;
        this.faultMapper = faultMapper;
        this.audit = audit;
    }

    public MaintainRecord amend(String recordId, String reason, String actor) {
        MaintainRecord v1 = recordMapper.selectById(recordId);
        if (v1 == null) {
            throw new BizException(404, "记录不存在");
        }
        if (!"CONFIRMED".equals(v1.confirmStatus)) {
            throw new BizException(422, "仅已归档记录可发起修订");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(422, "修订原因必填");
        }
        MaintainRecord v2 = new MaintainRecord();
        v2.id = Ids.next("rec");
        v2.elevatorName = v1.elevatorName;
        v2.elevatorCode = v1.elevatorCode;
        v2.useUnitId = v1.useUnitId;
        v2.useUnitName = v1.useUnitName;
        v2.elevatorId = v1.elevatorId;
        v2.workType = v1.workType;
        v2.workTypeCode = v1.workTypeCode;
        v2.workerName = v1.workerName;
        v2.assistantName = v1.assistantName;
        v2.workerPlatformId = v1.workerPlatformId;
        v2.assistantPlatformId = v1.assistantPlatformId;
        v2.checkinTime = v1.checkinTime;
        v2.checkoutTime = v1.checkoutTime;
        v2.duration = v1.duration;
        v2.itemsJson = v1.itemsJson;
        v2.photosJson = v1.photosJson;
        v2.workerSignatureUrl = v1.workerSignatureUrl;
        v2.assistantSignatureUrl = v1.assistantSignatureUrl;
        v2.problemCodesJson = v1.problemCodesJson;
        v2.version = (v1.version == null ? 1 : v1.version) + 1;
        v2.prevVersionId = v1.id;
        v2.amendReason = reason;
        v2.amendedBy = actor;
        v2.amendedAt = TimeUtil.now();
        v2.soloExempt = v1.soloExempt;
        v2.reportStatus = "DRAFT";
        v2.confirmStatus = "PENDING";
        v2.shareToken = "sg" + Long.toString(System.currentTimeMillis(), 36) + v2.id;
        v2.createdAt = TimeUtil.now();
        v2.previewContextJson = sha256(v2.itemsJson == null ? "" : v2.itemsJson);
        recordMapper.insert(v2);
        audit(actor, "record:amend", v2.id, "SUCCESS", reason);
        return v2;
    }

    public Fault proxyRepair(String faultId, Map<String, Object> body, String actor) {
        Fault f = faultMapper.selectById(faultId);
        if (f == null) {
            throw new BizException(404, "急修单不存在");
        }
        String source = str(body, "proxySource");
        String recordingNo = str(body, "recordingNo");
        if (source == null || !(source.equals("PHONE") || source.equals("WECHAT") || source.equals("ONSITE"))) {
            throw new BizException(422, "代报修来源须 PHONE/WECHAT/ONSITE");
        }
        if (recordingNo == null || !recordingNo.matches("^REC-\\d{4,6}$")) {
            throw new BizException(422, "录音编号须 REC-\\d{4,6}");
        }
        f.proxySource = source;
        f.recordingNo = recordingNo;
        f.proxyBy = actor;
        faultMapper.updateById(f);
        audit(actor, "order:create_proxy", faultId, "SUCCESS", source);
        return f;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void audit(String actor, String action, String target, String result, String reason) {
        SysAuditLog a = new SysAuditLog();
        a.actorId = actor;
        a.action = action;
        a.target = target;
        a.result = result;
        a.reason = reason;
        audit.append(a);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}