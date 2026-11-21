package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysAuditLog;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * M4 作业域：记录归档后修订（新版本 + 明确变更集，旧版本与签名永久保留）+ 代报修。
 * 修订不触发任何上报（P-5 interim，AGENTS §2.4 无自动重试）；仅已确认且已归档可发起。
 */
@Service
public class WorkDomainService {

    private final MaintainRecordMapper recordMapper;
    private final FaultMapper faultMapper;
    private final AuditLogService audit;
    private final RecordLifecycleService recordLifecycle;

    public WorkDomainService(MaintainRecordMapper recordMapper, FaultMapper faultMapper,
                             AuditLogService audit, RecordLifecycleService recordLifecycle) {
        this.recordMapper = recordMapper;
        this.faultMapper = faultMapper;
        this.audit = audit;
        this.recordLifecycle = recordLifecycle;
    }

    public MaintainRecord amend(String recordId, Map<String, Object> body, String actor) {
        MaintainRecord v1 = recordMapper.selectById(recordId);
        if (v1 == null) {
            throw new BizException(404, "记录不存在");
        }
        if (!"CONFIRMED".equals(v1.confirmStatus) || !"ARCHIVED".equals(v1.lifecycleStatus)) {
            throw new BizException(422, "仅已确认且已归档记录可发起修订");
        }
        String reason = str(body, "reason");
        if (reason == null || reason.isBlank()) {
            throw new BizException(422, "修订原因必填");
        }
        boolean changeItems = body != null && body.containsKey("items");
        boolean changeTodo = body != null && body.containsKey("todoDesc");
        boolean changeSafety = body != null && body.containsKey("safety");
        if (!changeItems && !changeTodo && !changeSafety) {
            throw new BizException(422, "请至少提交一项实际修订内容");
        }
        if (changeItems && !(body.get("items") instanceof List<?>)) {
            throw new BizException(422, "修订检查项必须为列表");
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
        v2.itemsJson = changeItems ? JsonUtil.write(body.get("items")) : v1.itemsJson;
        v2.photosJson = v1.photosJson;
        v2.safetyJson = changeSafety
                ? (body.get("safety") == null ? null : JsonUtil.write(body.get("safety"))) : v1.safetyJson;
        v2.todoDesc = changeTodo ? str(body, "todoDesc") : v1.todoDesc;
        v2.workerSignatureUrl = v1.workerSignatureUrl;
        v2.assistantSignatureUrl = v1.assistantSignatureUrl;
        v2.problemCodesJson = v1.problemCodesJson;
        v2.originalRecordId = v1.originalRecordId;
        v2.workOrderId = v1.workOrderId;
        v2.nextMaintenanceDate = v1.nextMaintenanceDate;
        v2.previewContextJson = v1.previewContextJson;
        v2.version = (v1.version == null ? 1 : v1.version) + 1;
        v2.prevVersionId = v1.id;
        v2.amendReason = reason;
        v2.amendedBy = actor;
        v2.amendedAt = TimeUtil.now();
        v2.soloExempt = v1.soloExempt;
        // 客户签署、归档文件、拒签和上一周期上报载荷均不复制；只能重新走复核/签署。
        v2.reportStatus = "DRAFT";
        v2.retryCount = 0;
        v2.confirmStatus = "PENDING";
        v2.lifecycleStatus = recordLifecycle.initialStatus();
        v2.shareToken = "sg" + Long.toString(System.currentTimeMillis(), 36) + v2.id;
        v2.createdAt = TimeUtil.now();
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
