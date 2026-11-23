package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.RecordVersionUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 使用单位确认：待确认列表/详情/本机确认/分享链接视图/凭 token 签字。 */
@Service
public class UnitRecordService {

    private final MaintainRecordMapper recordMapper;
    private final WorkOrderService workOrderService;
    private final FileStorageService fileStorageService;

    private RecordLifecycleService recordLifecycleService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRecordLifecycleService(RecordLifecycleService recordLifecycleService) {
        this.recordLifecycleService = recordLifecycleService;
    }

    public UnitRecordService(MaintainRecordMapper recordMapper, WorkOrderService workOrderService,
                             FileStorageService fileStorageService,
                             com.cqwlw.maintenance.mapper.ElevatorMapper elevatorMapper) {
        this.recordMapper = recordMapper;
        this.workOrderService = workOrderService;
        this.fileStorageService = fileStorageService;
        this.elevatorMapper = elevatorMapper;
    }

    private final com.cqwlw.maintenance.mapper.ElevatorMapper elevatorMapper;

    /** 统一令牌 + CLIENT 本单位谓词（旧门户令牌 UNIT_ADMIN 兜底）。 */
    public Map<String, Object> pendingAs(Map<String, String> query, java.util.Set<String> roles, String unitId) {
        requireClient(roles, unitId);
        List<MaintainRecord> candidates = recordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                        .eq(MaintainRecord::getConfirmStatus, "PENDING")
                        .eq(MaintainRecord::getLifecycleStatus, "PENDING_CLIENT_SIGNATURE")
                        .orderByDesc(MaintainRecord::getCreatedAt))
                .stream()
                // 内存双保险：与 SQL 同谓词（历史数据 lifecycle 为 NULL 不作为客户可见记录，§4.6 表）
                .filter(r -> "PENDING".equals(r.confirmStatus) && "PENDING_CLIENT_SIGNATURE".equals(r.lifecycleStatus))
                .collect(Collectors.toList());
        List<MaintainRecord> own = candidates.stream()
                .filter(r -> unitId.equals(r.useUnitId == null ? "" : r.useUnitId))
                .collect(Collectors.toList());
        Set<String> superseded = RecordVersionUtil.supersededIds(recordMapper,
                own.stream().map(r -> r.id).collect(Collectors.toList()));
        List<Map<String, Object>> list = own.stream()
                .filter(r -> !superseded.contains(r.id))
                .map(this::toClientMap)
                .collect(Collectors.toList());
        return workOrderService.paginate(list, query);
    }

    public Map<String, Object> getAs(String id, java.util.Set<String> roles, String unitId) {
        requireClient(roles, unitId);
        MaintainRecord r = findOr404(id);
        requireOwnUnit(r, unitId, "仅本单位记录可查看");
        requireViewable(r); // 查看门禁（T4-C2）：§4.6 可见性表，与签字门禁（requireSignable）分离
        return toClientMap(r);
    }

    public Map<String, Object> confirmAs(String id, Map<String, Object> body, java.util.Set<String> roles, String unitId) {
        return confirmAs(id, body, roles, unitId, null, null);
    }

    public Map<String, Object> confirmAs(String id, Map<String, Object> body, java.util.Set<String> roles,
                                         String unitId, String signerAccountId, String signerName) {
        requireClient(roles, unitId);
        MaintainRecord r = findOr404(id);
        requireOwnUnit(r, unitId, "仅本单位记录可确认");
        requireSignable(r);
        return signRecord(r, body, signerAccountId, signerName);
    }

    /** 批量签字：同一签名图一次确认多条待签记录。 */
    public Map<String, Object> batchConfirmAs(List<String> recordIds, Map<String, Object> body,
                                              java.util.Set<String> roles, String unitId,
                                              String signerAccountId, String signerName) {
        requireClient(roles, unitId);
        if (recordIds == null || recordIds.isEmpty()) {
            throw new BizException(422, "请选择要签署的记录");
        }
        List<String> ids = recordIds.stream()
                .filter(java.util.Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            throw new BizException(422, "请选择要签署的记录");
        }
        if (ids.size() > 20) {
            throw new BizException(422, "单次最多批量签署 20 条记录");
        }
        String signatureUrl = WorkOrderService.strOrEmpty(body.get("signatureUrl"));
        String signatureFileId = WorkOrderService.strOrEmpty(body.get("signatureFileId"));
        if (signatureUrl.isEmpty() && signatureFileId.isEmpty()) {
            throw new BizException(422, "请先完成签名");
        }
        int signed = 0;
        List<Map<String, Object>> failed = new java.util.ArrayList<>();
        for (String id : ids) {
            try {
                MaintainRecord r = findOr404(id);
                requireOwnUnit(r, unitId, "仅本单位记录可确认");
                requireSignable(r);
                signRecord(r, body, signerAccountId, signerName);
                signed++;
            } catch (BizException e) {
                failed.add(JsonUtil.map("id", id, "message", e.getMessage()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", failed.isEmpty());
        out.put("signed", signed);
        out.put("failed", failed);
        return out;
    }

    /** 仅待签记录可条件更新，并发时以首签为准。 */
    private Map<String, Object> signRecord(MaintainRecord r, Map<String, Object> body) {
        return signRecord(r, body, null, null);
    }

    private Map<String, Object> signRecord(MaintainRecord r, Map<String, Object> body,
                                           String signerAccountId, String signerName) {
        String signatureUrl = WorkOrderService.strOrEmpty(body.get("signatureUrl"));
        String signatureFileId = WorkOrderService.strOrEmpty(body.get("signatureFileId"));
        if (signatureUrl.isEmpty() && signatureFileId.isEmpty()) {
            throw new BizException(422, "请先完成签名");
        }
        if (!signatureUrl.isEmpty()) {
            String fileId = FileStorageService.fileIdFromUrl(signatureUrl);
            if (fileId == null) {
                throw new BizException(422, "签名图片必须来自本系统文件");
            }
            signatureUrl = "/files/" + fileId;
        }
        java.time.LocalDateTime signedAt = TimeUtil.now();
        LambdaUpdateWrapper<MaintainRecord> uw = new LambdaUpdateWrapper<MaintainRecord>()
                .eq(MaintainRecord::getId, r.id)
                .eq(MaintainRecord::getConfirmStatus, "PENDING")
                .eq(MaintainRecord::getLifecycleStatus, "PENDING_CLIENT_SIGNATURE")
                .set(MaintainRecord::getConfirmStatus, "CONFIRMED")
                .set(MaintainRecord::getLifecycleStatus, "SIGNED")
                .set(MaintainRecord::getSignatureFileId, signatureFileId)
                .set(MaintainRecord::getSignatureUrl, signatureUrl);
        if (signerAccountId != null && !signerAccountId.isBlank()) {
            uw.set(MaintainRecord::getClientAccountId, signerAccountId)
              .set(MaintainRecord::getClientSignerName, signerName == null ? "" : signerName)
              .set(MaintainRecord::getClientSignedAt, signedAt);
        }
        int affected = recordMapper.update(null, uw);
        if (affected == 0) {
            throw new BizException(422, "记录已被签署（先签为准），请刷新后查看");
        }
        // 落库成功后才回写内存态，供 onClientSigned 归档投影（PDF 需最新签字字段）
        r.confirmStatus = "CONFIRMED";
        r.lifecycleStatus = "SIGNED";
        r.signatureFileId = signatureFileId;
        r.signatureUrl = signatureUrl;
        if (signerAccountId != null && !signerAccountId.isBlank()) {
            r.clientAccountId = signerAccountId;
            r.clientSignerName = signerName;
            r.clientSignedAt = signedAt;
        }
        if (recordLifecycleService != null) {
            recordLifecycleService.onClientSigned(r);
        }
        appendSignAudit(r, signerAccountId, signerName);
        return JsonUtil.map("ok", true);
    }

    private void appendSignAudit(MaintainRecord r, String signerAccountId, String signerName) {
        if (auditLogService == null) {
            return;
        }
        try {
            com.cqwlw.maintenance.entity.SysAuditLog a = new com.cqwlw.maintenance.entity.SysAuditLog();
            a.id = com.cqwlw.maintenance.common.Ids.next("audit");
            a.actorId = signerAccountId == null ? "" : signerAccountId;
            a.actorRole = signerAccountId == null ? "ANONYMOUS" : "CUSTOMER";
            a.action = "CLIENT_RECORD_SIGN";
            a.target = r.id;
            a.useUnitId = r.useUnitId;
            a.reason = "客户签字：" + (signerName == null ? "匿名链" : signerName);
            a.result = "SUCCESS";
            a.createdAt = TimeUtil.now();
            auditLogService.append(a);
        } catch (Exception e) {
            // 审计落库失败不影响签字主流程（AuditLogService 契约）
        }
    }

    private AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAuditLogService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    private ClientSignSlaService clientSignSlaService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientSignSlaService(ClientSignSlaService clientSignSlaService) {
        this.clientSignSlaService = clientSignSlaService;
    }

    private void requireClient(java.util.Set<String> roles, String unitId) {
        if (roles == null || roles.stream().noneMatch(
                r -> RoleCatalog.principalTypeOf(r) == RoleCatalog.PrincipalType.CUSTOMER)) {
            throw new BizException(1403, "仅使用单位可访问门户记录");
        }
        if (unitId == null || unitId.isEmpty()) {
            throw new BizException(1403, "缺少单位锚点");
        }
    }

    private void requireOwnUnit(MaintainRecord r, String unitId, String message) {
        if (!unitId.equals(unitOf(r))) {
            throw new BizException(1403, message);
        }
    }

    /** 签字门禁链④⑤⑥：唯一可签状态 PENDING_CLIENT_SIGNATURE + confirmStatus=PENDING + 链末端最新版本，任一失败 422 */
    private void requireSignable(MaintainRecord r) {
        if (!"PENDING_CLIENT_SIGNATURE".equals(r.lifecycleStatus)) {
            throw new BizException(422, "记录不在待使用单位签署状态");
        }
        if (!"PENDING".equals(r.confirmStatus)) {
            throw new BizException(422, "记录已签署，不能重复签字");
        }
        requireLatestVersion(r, "记录已被新版本替代，请签署最新记录");
    }

    /** 查看门禁：客户只读可见集合 =PENDING_CLIENT_SIGNATURE（可签）/ SIGNED / ARCHIVED / REFUSED_RETURNED（只读）； */
    private void requireViewable(MaintainRecord r) {
        String lc = r.lifecycleStatus;
        if (!"PENDING_CLIENT_SIGNATURE".equals(lc) && !"SIGNED".equals(lc)
                && !"ARCHIVED".equals(lc) && !"REFUSED_RETURNED".equals(lc)) {
            throw new BizException(422, "记录当前状态客户不可见");
        }
        requireLatestVersion(r, "记录已被新版本替代，请查看最新记录");
    }

    private void requireLatestVersion(MaintainRecord r, String message) {
        if (!isLatestVersion(r)) {
            throw new BizException(422, message);
        }
    }

    private boolean isLatestVersion(MaintainRecord r) {
        Long next = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getPrevVersionId, r.id));
        return next == null || next == 0;
    }

    private String unitOf(MaintainRecord r) {
        return r.useUnitId == null ? "" : r.useUnitId;
    }

    public MaintainRecord findOr404(String id) {
        MaintainRecord r = recordMapper.selectById(id);
        if (r == null) {
            throw new BizException(1404, "维保记录不存在");
        }
        return r;
    }

    private MaintainRecord findOr401(String id) {
        MaintainRecord r = recordMapper.selectById(id);
        if (r == null) {
            throw new BizException(401, "确认链接无效或已失效");
        }
        return r;
    }

    public Map<String, Object> pending(Map<String, String> query) {
        List<Map<String, Object>> list = recordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                        .eq(MaintainRecord::getConfirmStatus, "PENDING")
                        .orderByDesc(MaintainRecord::getCreatedAt))
                .stream().map(this::toMap).collect(Collectors.toList());
        return workOrderService.paginate(list, query);
    }

    public Map<String, Object> get(String id) {
        return toMap(findOr404(id));
    }

    /** 内部直确路径（UnitRecordConfirmTest 锁定）：客户令牌路径一律走 confirmAs 门禁链 + 条件更新，不经此方法 */
    public Map<String, Object> confirm(String id, Map<String, Object> body) {
        MaintainRecord r = findOr404(id);
        if ("CONFIRMED".equals(r.confirmStatus)) {
            return JsonUtil.map("ok", true, "already", true);
        }
        String signatureUrl = WorkOrderService.strOrEmpty(body.get("signatureUrl"));
        String signatureFileId = WorkOrderService.strOrEmpty(body.get("signatureFileId"));
        if (signatureUrl.isEmpty() && signatureFileId.isEmpty()) {
            throw new BizException(422, "请先完成签名");
        }
        r.confirmStatus = "CONFIRMED";
        r.satisfaction = body.get("satisfaction") == null ? 0 : ((Number) body.get("satisfaction")).intValue();
        r.signatureFileId = signatureFileId;
        r.signatureUrl = signatureUrl;
        recordMapper.updateById(r);
        if (recordLifecycleService != null) {
            recordLifecycleService.onClientSigned(r); // M1：SIGNED→自动归档（不触发平台调用）
        }
        return JsonUtil.map("ok", true);
    }

    public Map<String, Object> signView(String id, String token) {
        MaintainRecord r = findOr401(id); // C-25⑧：不存在 → 401 同错 token 文案（匿名端点无存在性 oracle）
        if (token == null || token.isEmpty() || !token.equals(r.shareToken)) {
            throw new BizException(401, "确认链接无效或已失效");
        }
        List<Map<String, Object>> allItems = resolveItems(r.itemsJson);
        List<String> photos = resolveRecordPhotos(allItems, r.photosJson);
        // 白名单禁令不变：不回显 shareToken/reportPayload/context/平台ID/refuseReason/satisfaction
        List<Map<String, Object>> items = previewItems(allItems);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmed", "CONFIRMED".equals(r.confirmStatus));
        m.put("confirmStatus", WorkOrderService.nz(r.confirmStatus));
        m.put("elevatorName", WorkOrderService.nz(r.elevatorName));
        m.put("elevatorCode", WorkOrderService.nz(r.elevatorCode));
        m.put("useUnitName", WorkOrderService.nz(r.useUnitName));
        m.put("workDate", TimeUtil.date(r.checkoutTime != null ? r.checkoutTime : r.checkinTime));
        m.put("workType", WorkOrderService.nz(r.workType));
        m.put("workerName", WorkOrderService.nz(r.workerName));
        m.put("assistantName", WorkOrderService.nz(r.assistantName));
        m.put("checkinTime", TimeUtil.format(r.checkinTime));
        m.put("checkoutTime", TimeUtil.format(r.checkoutTime));
        m.put("duration", WorkOrderService.nz(r.duration));
        m.put("items", items);
        m.put("itemTotal", items.size());
        m.put("photos", photos);
        m.put("photoCount", photos.size());
        m.put("recordVersion", r.version);
        m.put("hashPrefix", hashPrefix(r.archiveHash));
        m.put("signatureUrl", fileStorageService.resolveStoredUrl(r.signatureUrl));
        if (clientSignSlaService != null) {
            java.time.LocalDateTime base = clientSignSlaService.baseOf(r);
            java.time.LocalDateTime deadline = clientSignSlaService.deadlineOf(r);
            java.time.LocalDateTime now = TimeUtil.now();
            m.put("signRequestedAt", TimeUtil.format(base));
            m.put("slaHours", clientSignSlaService.slaHours());
            m.put("signDeadline", deadline == null ? "" : TimeUtil.format(deadline));
            m.put("overdue", deadline != null && now.isAfter(deadline));
            m.put("serverNow", TimeUtil.format(now));
        }
        return m;
    }

    /** 归档哈希展示前缀（SHA-256 前 8 位，原型屏 11）；空/短值原样收敛为空串或全量 */
    private static String hashPrefix(String archiveHash) {
        if (archiveHash == null || archiveHash.isEmpty()) {
            return "";
        }
        return archiveHash.substring(0, Math.min(8, archiveHash.length()));
    }

    /** 匿名分享链接签字：token 即凭证（401 先于状态披露）； */
    public Map<String, Object> confirmByToken(String id, String token, Map<String, Object> body) {
        MaintainRecord r = findOr401(id); // C-25⑧：不存在 → 401 同错 token 文案（匿名端点无存在性 oracle）
        if (token == null || token.isEmpty() || !token.equals(r.shareToken)) {
            throw new BizException(401, "确认链接无效或已失效");
        }
        requireSignable(r);
        return signRecord(r, body);
    }

    public Map<String, Object> signatureUploadByToken(String id, String token,
                                                      org.springframework.web.multipart.MultipartFile file,
                                                      String schemeHost) throws java.io.IOException {
        MaintainRecord r = findOr401(id);
        if (token == null || token.isEmpty() || !token.equals(r.shareToken)) {
            throw new BizException(401, "确认链接无效或已失效");
        }
        requireSignable(r);
        if (file == null || file.isEmpty()) {
            throw new BizException(422, "缺少上传文件");
        }
        com.cqwlw.maintenance.entity.AppFile f = fileStorageService.store(file, schemeHost);
        return JsonUtil.map("fileId", f.id, "url", f.url == null ? "" : f.url);
    }

    public Map<String, Object> toMap(MaintainRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("elevatorName", r.elevatorName);
        m.put("elevatorCode", r.elevatorCode);
        m.put("workType", r.workType);
        m.put("workTypeCode", r.workTypeCode);
        m.put("workerName", r.workerName);
        m.put("assistantName", r.assistantName);
        m.put("workerPlatformId", r.workerPlatformId);
        m.put("assistantPlatformId", r.assistantPlatformId);
        m.put("checkinTime", TimeUtil.format(r.checkinTime));
        m.put("checkoutTime", TimeUtil.format(r.checkoutTime));
        m.put("duration", r.duration);
        List<Map<String, Object>> items = resolveItems(r.itemsJson);
        m.put("items", items);
        m.put("photos", resolveRecordPhotos(items, r.photosJson));
        m.put("workerSignatureUrl", fileStorageService.resolveStoredUrl(r.workerSignatureUrl));
        m.put("assistantSignatureUrl", fileStorageService.resolveStoredUrl(r.assistantSignatureUrl));
        m.put("problemCodes", JsonUtil.readStringList(r.problemCodesJson));
        m.put("originalRecordId", r.originalRecordId);
        m.put("reportStatus", r.reportStatus);
        m.put("uploadStatus", uploadStatus(r.reportStatus));
        m.put("retryCount", r.retryCount);
        m.put("nextMaintenanceDate", TimeUtil.formatDate(r.nextMaintenanceDate));
        m.put("confirmStatus", r.confirmStatus);
        m.put("lifecycleStatus", r.lifecycleStatus);
        m.put("version", r.version);
        m.put("prevVersionId", r.prevVersionId);
        m.put("hashPrefix", hashPrefix(r.archiveHash));
        m.put("archivedAt", TimeUtil.format(r.archivedAt));
        m.put("satisfaction", r.satisfaction);
        m.put("refuseCode", WorkOrderService.nz(r.refuseCode));
        m.put("refuseReason", WorkOrderService.nz(r.refuseReason));
        m.put("signatureFileId", r.signatureFileId);
        m.put("signatureUrl", fileStorageService.resolveStoredUrl(r.signatureUrl));
        m.put("shareToken", r.shareToken);
        m.put("reportPayload", r.reportPayloadJson == null ? null : JsonUtil.readMap(r.reportPayloadJson));
        m.put("context", r.previewContextJson == null ? Map.of() : JsonUtil.readMap(r.previewContextJson));
        return m;
    }

    /** 与 {@link #toMap} 的差异：① 不返回 shareToken/reportPayload/幂等键等预览用不到的字段（纵深防御，也避免管理端页面成为匿名签署链接的泄露源）； */
    public Map<String, Object> adminPreview(MaintainRecord r) {
        List<Map<String, Object>> allItems = resolveItems(r.itemsJson);
        List<String> photos = resolveRecordPhotos(allItems, r.photosJson);
        List<Map<String, Object>> items = previewItems(allItems);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("elevatorCode", WorkOrderService.nz(r.elevatorCode));
        m.put("elevatorName", WorkOrderService.nz(r.elevatorName));
        m.put("workType", WorkOrderService.nz(r.workType));
        m.put("workTypeCode", WorkOrderService.nz(r.workTypeCode));
        m.put("workerName", WorkOrderService.nz(r.workerName));
        m.put("assistantName", WorkOrderService.nz(r.assistantName));
        m.put("checkinTime", TimeUtil.format(r.checkinTime));
        m.put("checkoutTime", TimeUtil.format(r.checkoutTime));
        m.put("duration", WorkOrderService.nz(r.duration));
        m.put("problemCodes", JsonUtil.readStringList(r.problemCodesJson));
        m.put("originalRecordId", WorkOrderService.nz(r.originalRecordId));
        m.put("confirmStatus", WorkOrderService.nz(r.confirmStatus));
        m.put("lifecycleStatus", WorkOrderService.nz(r.lifecycleStatus));
        m.put("refuseCode", WorkOrderService.nz(r.refuseCode));
        m.put("refuseReason", WorkOrderService.nz(r.refuseReason));
        m.put("reportStatus", WorkOrderService.nz(r.reportStatus));
        m.put("version", r.version);
        m.put("archivedAt", TimeUtil.format(r.archivedAt));
        m.put("hashPrefix", hashPrefix(r.archiveHash));
        // 检查项明细（异常项由前端标红，不做任何「已整改」虚报）
        m.put("items", items);
        m.put("itemTotal", items.size());
        m.put("itemAbnormal", (int) items.stream().filter(i -> "ABNORMAL".equals(i.get("result"))).count());
        // 现场照片墙（已重签 URL，前端缩略图 + 点击放大）
        m.put("photos", photos);
        m.put("photoCount", photos.size());
        // 三方签字：主检 / 配合人 / 使用单位安全管理人员；
        m.put("workerSignatureUrl", fileStorageService.resolveStoredUrl(r.workerSignatureUrl));
        m.put("assistantSignatureUrl", fileStorageService.resolveStoredUrl(r.assistantSignatureUrl));
        m.put("clientSignatureUrl", fileStorageService.resolveStoredUrl(r.signatureUrl));
        m.put("clientSignerName", WorkOrderService.nz(r.clientSignerName));
        m.put("clientSignedAt", TimeUtil.format(r.clientSignedAt));
        m.put("safety", r.safetyJson == null || r.safetyJson.isBlank()
                ? Map.of() : JsonUtil.readMap(r.safetyJson));
        m.put("todoDesc", WorkOrderService.nz(r.todoDesc));
        m.put("context", r.previewContextJson == null
                ? Map.of() : JsonUtil.readMap(r.previewContextJson));
        return m;
    }

    /** 检查项明细投影（过滤 notInThisRun 未执行项——不得把「本轮未执行」显示成「正常」误导核对）：名称/结果/实测值/异常描述/跳过原因/周期标签/照片URL，均为记录内容字段、不含内部标识。 */
    private List<Map<String, Object>> previewItems(List<Map<String, Object>> allItems) {
        return allItems.stream()
                .filter(i -> !Boolean.TRUE.equals(i.get("notInThisRun")))
                .map(i -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", i.get("id"));
                    c.put("itemCode", i.get("itemCode"));
                    c.put("name", i.get("name"));
                    c.put("result", i.get("result"));
                    c.put("problemCode", i.get("problemCode"));
                    c.put("value", i.get("value"));
                    c.put("valueUnit", i.get("valueUnit"));
                    c.put("valueText", i.get("valueText"));
                    c.put("abnormalDesc", i.get("abnormalDesc"));
                    c.put("skipReason", i.get("skipReason"));
                    c.put("freqLabel", i.get("freqLabel"));
                    c.put("photos", i.get("photos"));
                    return c;
                })
                .collect(Collectors.toList());
    }

    /** 客户投影不返回上报、复核、分享和平台人员字段。 */
    private Map<String, Object> toClientMap(MaintainRecord r) {
        List<Map<String, Object>> resolved = resolveItems(r.itemsJson);
        // items 同为白名单投影：仅 名称/结果/实测值/照片URL（照片已按 fileId 重签）
        List<Map<String, Object>> items = resolved.stream().map(i -> {
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("name", i.get("name"));
            c.put("result", i.get("result"));
            c.put("value", i.get("value"));
            c.put("photos", i.get("photos"));
            return c;
        }).collect(Collectors.toList());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("version", r.version);
        m.put("prevVersionId", r.prevVersionId);
        m.put("lifecycleStatus", r.lifecycleStatus);
        m.put("elevatorCode", r.elevatorCode);
        m.put("elevatorName", r.elevatorName);
        m.put("workType", r.workType);
        m.put("workTypeCode", r.workTypeCode);
        m.put("workerName", r.workerName);
        m.put("assistantName", r.assistantName);
        m.put("checkinTime", TimeUtil.format(r.checkinTime));
        m.put("checkoutTime", TimeUtil.format(r.checkoutTime));
        m.put("duration", r.duration);
        m.put("items", items);
        m.put("photos", resolveRecordPhotos(resolved, r.photosJson));
        m.put("problemCodes", JsonUtil.readStringList(r.problemCodesJson));
        m.put("todoDesc", WorkOrderService.nz(r.todoDesc));
        m.put("workerSignatureUrl", fileStorageService.resolveStoredUrl(r.workerSignatureUrl));
        m.put("assistantSignatureUrl", fileStorageService.resolveStoredUrl(r.assistantSignatureUrl));
        m.put("confirmStatus", r.confirmStatus);
        m.put("signatureUrl", fileStorageService.resolveStoredUrl(r.signatureUrl));
        // 归档 PDF 短时效重签 URL（§4.7「下载」）；未归档为空串
        m.put("pdfUrl", r.archiveFileId == null || r.archiveFileId.isEmpty()
                ? "" : fileStorageService.urlOf(r.archiveFileId));
        return m;
    }

    /** 记录详情是长期读路径：照片优先按 photoFileIds 重签为当前可访问 URL，历史快照里的本地临时路径（wxfile://、http://tmp/）不可跨设备访问，必须过滤掉。 */
    private List<Map<String, Object>> resolveItems(String itemsJson) {
        if (itemsJson == null || itemsJson.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> items = JsonUtil.readList(itemsJson);
        for (Map<String, Object> item : items) {
            List<String> urls = WorkOrderService.asList(item.get("photoFileIds")).stream()
                    .map(String::valueOf)
                    .map(fileStorageService::urlOf)
                    .filter(WorkOrderService::isHttpUrl)
                    .collect(Collectors.toList());
            if (urls.isEmpty()) {
                Object photos = item.get("photos");
                if (photos instanceof List<?> list) {
                    urls = list.stream()
                            .map(v -> fileStorageService.resolveStoredUrl(String.valueOf(v)))
                            .filter(WorkOrderService::isHttpUrl)
                            .collect(Collectors.toList());
                }
            }
            item.put("photos", urls);
        }
        return items;
    }

    /** 记录级照片墙与检查项明细同源：优先取已解析的检查项照片（可恢复旧记录的 fileId），仅当明细没有照片时才回退到冻结的 photos_json。 */
    private List<String> resolveRecordPhotos(List<Map<String, Object>> items, String photosJson) {
        List<String> fromItems = items.stream()
                .flatMap(i -> WorkOrderService.asList(i.get("photos")).stream()
                        .map(String::valueOf)
                        .filter(WorkOrderService::isHttpUrl))
                .collect(Collectors.toList());
        if (!fromItems.isEmpty()) {
            return fromItems;
        }
        return JsonUtil.readStringList(photosJson).stream()
                .map(fileStorageService::resolveStoredUrl)
                .filter(WorkOrderService::isHttpUrl)
                .collect(Collectors.toList());
    }

    static String uploadStatus(String reportStatus) {
        if ("REPORTED".equals(reportStatus)) {
            return "SUCCESS";
        }
        if ("FAILED".equals(reportStatus)) {
            return "FAILED";
        }
        return "PENDING";
    }
}
