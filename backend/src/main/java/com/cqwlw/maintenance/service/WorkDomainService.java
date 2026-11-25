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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkDomainService {

    private final MaintainRecordMapper recordMapper;
    private final FaultMapper faultMapper;
    private final AuditLogService audit;
    private final RecordLifecycleService recordLifecycle;
    private final DirectoryService directoryService;
    private final TransactionTemplate transactionTemplate;

    public WorkDomainService(MaintainRecordMapper recordMapper, FaultMapper faultMapper,
                             AuditLogService audit, RecordLifecycleService recordLifecycle,
                             DirectoryService directoryService, TransactionTemplate transactionTemplate) {
        this.recordMapper = recordMapper;
        this.faultMapper = faultMapper;
        this.audit = audit;
        this.recordLifecycle = recordLifecycle;
        this.directoryService = directoryService;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public MaintainRecord amend(String recordId, Map<String, Object> body, String actor) {
        MaintainRecord v1 = recordMapper.selectByIdForUpdate(recordId);
        if (v1 == null) {
            throw new BizException(404, "记录不存在");
        }
        if (!"CONFIRMED".equals(v1.confirmStatus) || !"ARCHIVED".equals(v1.lifecycleStatus)) {
            throw new BizException(422, "仅已确认且已归档记录可发起修订");
        }
        if (v1.reportStatus != null && Set.of("REUPLOADING", "LEGACY_UPLOADING").contains(v1.reportStatus)) {
            throw new BizException(422, "记录正在上报，完成前禁止修订，避免旧快照覆盖新版本");
        }
        Long children = recordMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getPrevVersionId, v1.id));
        if (children != null && children > 0) {
            throw new BizException(422, "仅最新归档版本可发起修订");
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

        MergedItems merged = changeItems ? mergeItems(v1.itemsJson, body.get("items"))
                : new MergedItems(v1.itemsJson, v1.problemCodesJson);

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
        v2.itemsJson = merged.itemsJson();
        v2.photosJson = v1.photosJson;
        v2.safetyJson = changeSafety
                ? (body.get("safety") == null ? null : JsonUtil.write(body.get("safety"))) : v1.safetyJson;
        v2.todoDesc = changeTodo ? str(body, "todoDesc") : v1.todoDesc;
        v2.workerSignatureUrl = v1.workerSignatureUrl;
        v2.assistantSignatureUrl = v1.assistantSignatureUrl;
        v2.problemCodesJson = merged.problemCodesJson();
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
        v2.shareToken = Ids.nextShareToken();
        v2.createdAt = TimeUtil.now();
        recordMapper.insert(v2);
        audit(actor, "record:amend", v2.id, "SUCCESS", reason);
        return v2;
    }

    private record MergedItems(String itemsJson, String problemCodesJson) {
    }

    private MergedItems mergeItems(String originalJson, Object rawPatches) {
        List<Map<String, Object>> original = JsonUtil.readList(originalJson);
        List<?> patches = (List<?>) rawPatches;
        if (original == null || patches.isEmpty()) {
            throw new BizException(422, "修订检查项不能为空");
        }
        Map<String, Map<String, Object>> patchById = new java.util.LinkedHashMap<>();
        for (Object raw : patches) {
            if (!(raw instanceof Map<?, ?> rawMap)) {
                throw new BizException(422, "修订检查项格式不正确");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> patch = (Map<String, Object>) rawMap;
            String key = itemKey(patch);
            if (patchById.put(key, patch) != null) {
                throw new BizException(422, "修订检查项重复：" + key);
            }
        }
        int executedCount = (int) original.stream().filter(i -> !Boolean.TRUE.equals(i.get("notInThisRun"))).count();
        if (executedCount == 0 || patchById.size() != executedCount) {
            throw new BizException(422, "修订检查项必须完整对应原记录，禁止缺项或新增项");
        }

        List<Map<String, Object>> merged = new java.util.ArrayList<>();
        List<String> problemCodes = new java.util.ArrayList<>();
        for (Map<String, Object> source : original) {
            Map<String, Object> item = new java.util.LinkedHashMap<>(source);
            if (!Boolean.TRUE.equals(source.get("notInThisRun"))) {
                String key = itemKey(source);
                Map<String, Object> patch = patchById.remove(key);
                if (patch == null) {
                    throw new BizException(422, "修订缺少原检查项：" + key);
                }
                applyItemPatch(item, patch);
                validateItemEvidence(item);
                if ("ABNORMAL".equals(str(item, "result"))) {
                    problemCodes.add(str(item, "problemCode"));
                }
            }
            merged.add(item);
        }
        if (!patchById.isEmpty()) {
            throw new BizException(422, "修订包含原记录不存在的检查项");
        }
        if (problemCodes.isEmpty()) {
            problemCodes = List.of("S0");
        }
        return new MergedItems(JsonUtil.write(merged), JsonUtil.write(problemCodes));
    }

    private void applyItemPatch(Map<String, Object> item, Map<String, Object> patch) {
        for (String field : List.of("result", "value", "valueUnit", "valueText", "abnormalDesc", "skipReason", "problemCode")) {
            if (patch.containsKey(field)) {
                item.put(field, patch.get(field));
            }
        }
        String result = str(item, "result");
        if (result == null || result.isBlank()) {
            throw new BizException(422, "检查项结果必填：" + itemKey(item));
        }
        item.put("result", result.trim());
        if ("NA".equals(result.trim()) && (str(item, "skipReason") == null || str(item, "skipReason").isBlank())) {
            throw new BizException(422, "标记不适用必须填写跳过原因：" + itemKey(item));
        }
        if (patch.containsKey("value")) {
            item.put("value", parseNumericValue(patch.get("value")));
        }
        if ("ABNORMAL".equals(result.trim()) && listValue(item.get("photoFileIds")).isEmpty()
                && listValue(item.get("photos")).isEmpty()) {
            throw new BizException(422, "异常项必须至少附 1 张照片：" + itemKey(item));
        }
        if ("ABNORMAL".equals(result.trim())) {
            if (str(item, "abnormalDesc") == null || str(item, "abnormalDesc").isBlank()) {
                throw new BizException(422, "异常项必须填写异常说明：" + itemKey(item));
            }
            String code = str(item, "problemCode");
            if (code == null || !Set.of("S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7").contains(code.trim())) {
                throw new BizException(422, "异常项必须显式选择 S0—S7：" + itemKey(item));
            }
            item.put("problemCode", code.trim());
        } else {
            item.put("problemCode", "");
            item.put("abnormalDesc", "");
        }
    }

    private void validateItemEvidence(Map<String, Object> item) {
        if (Boolean.TRUE.equals(item.get("isKey")) && Boolean.TRUE.equals(item.get("photoRequired"))
                && !"NA".equals(str(item, "result"))
                && listValue(item.get("photoFileIds")).isEmpty() && listValue(item.get("photos")).isEmpty()) {
            throw new BizException(422, "关键项「" + str(item, "name") + "」须至少附 1 张照片");
        }
    }

    private static Double parseNumericValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            if (!Double.isFinite(parsed)) {
                throw new BizException(422, "实测值必须为有限数字");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BizException(422, "实测值必须为数字");
        }
    }

    private static List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String itemKey(Map<String, Object> item) {
        String code = str(item, "itemCode");
        if (code != null && !code.isBlank()) {
            return code;
        }
        String id = str(item, "id");
        if (id != null && !id.isBlank()) {
            return id;
        }
        throw new BizException(422, "检查项缺少稳定 id/itemCode，无法安全修订");
    }

    /** 组合代报修：先校验全部参数，再在同一事务内建单并落来源/录音与审计，失败不留半成品。 */
    public Map<String, Object> createProxyFault(Map<String, Object> body, String actor) {
        return transactionTemplate.execute(status -> doCreateProxyFault(body, actor));
    }

    private Map<String, Object> doCreateProxyFault(Map<String, Object> body, String actor) {
        String source = proxySource(body);
        String recordingNo = recordingNo(body);
        Map<String, Object> view = directoryService.createFault(body, actor);
        String faultId = String.valueOf(view.get("id"));
        Fault f = faultMapper.selectById(faultId);
        f.proxySource = source;
        f.recordingNo = recordingNo;
        f.proxyBy = actor;
        faultMapper.updateById(f);
        audit(actor, "order:create_proxy", faultId, "SUCCESS", source);
        return view;
    }

    public Fault proxyRepair(String faultId, Map<String, Object> body, String actor) {
        Fault f = faultMapper.selectById(faultId);
        if (f == null) {
            throw new BizException(404, "急修单不存在");
        }
        String source = proxySource(body);
        String recordingNo = recordingNo(body);
        f.proxySource = source;
        f.recordingNo = recordingNo;
        f.proxyBy = actor;
        faultMapper.updateById(f);
        audit(actor, "order:create_proxy", faultId, "SUCCESS", source);
        return f;
    }

    private static String proxySource(Map<String, Object> body) {
        String source = str(body, "proxySource");
        if (source == null || !(source.equals("PHONE") || source.equals("WECHAT") || source.equals("ONSITE"))) {
            throw new BizException(422, "代报修来源须 PHONE/WECHAT/ONSITE");
        }
        return source;
    }

    private static String recordingNo(Map<String, Object> body) {
        String recordingNo = str(body, "recordingNo");
        if (recordingNo == null || !recordingNo.matches("^REC-\\d{4,6}$")) {
            throw new BizException(422, "录音编号须 REC-\\d{4,6}");
        }
        return recordingNo;
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
