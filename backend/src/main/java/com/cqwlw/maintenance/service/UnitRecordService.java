package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 使用单位确认（docs/04 A.3）：待确认列表/详情/本机确认/分享链接视图/凭 token 签字。
 */
@Service
public class UnitRecordService {

    private final MaintainRecordMapper recordMapper;
    private final WorkOrderService workOrderService;
    private final FileStorageService fileStorageService;

    /** M1 生命周期钩子（setter 注入；生产 Spring 必注入） */
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

    /**
     * M1：统一令牌 + CLIENT 本单位谓词（旧门户令牌 UNIT_ADMIN 兜底）。
     * P1 T4（docs/14 §4.6）：客户待签列表只含 本单位 + 唯一可签状态 PENDING_CLIENT_SIGNATURE + 链末端最新版本，
     * 投影一律走 toClientMap 白名单（内部字段不外泄）。
     */
    public Map<String, Object> pendingAs(Map<String, String> query, java.util.Set<String> roles, String unitId) {
        requireClient(roles, unitId);
        List<Map<String, Object>> list = recordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                        .eq(MaintainRecord::getConfirmStatus, "PENDING")
                        .eq(MaintainRecord::getLifecycleStatus, "PENDING_CLIENT_SIGNATURE")
                        .orderByDesc(MaintainRecord::getCreatedAt))
                .stream()
                // 内存双保险：与 SQL 同谓词（历史数据 lifecycle 为 NULL 不作为客户可见记录，§4.6 表）
                .filter(r -> "PENDING".equals(r.confirmStatus) && "PENDING_CLIENT_SIGNATURE".equals(r.lifecycleStatus))
                .filter(r -> unitId.equals(unitOf(r)) && isLatestVersion(r))
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
        requireClient(roles, unitId);
        MaintainRecord r = findOr404(id);
        requireOwnUnit(r, unitId, "仅本单位记录可确认");
        requireSignable(r);
        return signRecord(r, body);
    }

    /**
     * 签字落库（confirmAs / confirmByToken 共用，docs/14 §4.6 规则 2/3，门禁须已前置通过）：
     * 条件更新 WHERE confirm_status='PENDING' AND lifecycle_status='PENDING_CLIENT_SIGNATURE'，
     * 受影响行 0 → 422 已签（并发先签为准，不覆盖首签）；satisfaction 不接收（§4.7 评价关闭，
     * 不塞进签字端点；旧实现对字符串入参还会 CCE→500）；落库成功后才回写内存态并归档
     * （onClientSigned 只置 SIGNED+归档，不注入上报服务，结构保证不触发平台 2.6）。
     */
    private Map<String, Object> signRecord(MaintainRecord r, Map<String, Object> body) {
        String signatureUrl = WorkOrderService.strOrEmpty(body.get("signatureUrl"));
        String signatureFileId = WorkOrderService.strOrEmpty(body.get("signatureFileId"));
        if (signatureUrl.isEmpty() && signatureFileId.isEmpty()) {
            throw new BizException(422, "请先完成签名");
        }
        int affected = recordMapper.update(null, new LambdaUpdateWrapper<MaintainRecord>()
                .eq(MaintainRecord::getId, r.id)
                .eq(MaintainRecord::getConfirmStatus, "PENDING")
                .eq(MaintainRecord::getLifecycleStatus, "PENDING_CLIENT_SIGNATURE")
                .set(MaintainRecord::getConfirmStatus, "CONFIRMED")
                .set(MaintainRecord::getSignatureFileId, signatureFileId)
                .set(MaintainRecord::getSignatureUrl, signatureUrl));
        if (affected == 0) {
            throw new BizException(422, "记录已被签署（先签为准），请刷新后查看");
        }
        // 落库成功后才回写内存态，供 onClientSigned 归档投影（PDF 需最新签字字段）
        r.confirmStatus = "CONFIRMED";
        r.signatureFileId = signatureFileId;
        r.signatureUrl = signatureUrl;
        if (recordLifecycleService != null) {
            recordLifecycleService.onClientSigned(r);
        }
        return JsonUtil.map("ok", true);
    }

    /**
     * 门禁链①②（docs/14 §4.6 规则 2）：principalType=CUSTOMER（T1 RoleCatalog，CLIENT_ADMIN/CLIENT_SIGNER
     * 及 UNIT_ADMIN 别名）+ token useUnitId 锚点。链末「账号启用」由 AuthInterceptor 会话校验保证
     * （删除→401、停用 bumpScopeVersion→sv 不符 401、单会话顶号→sid 不符 401），服务层无 empId 不重复查。
     */
    private void requireClient(java.util.Set<String> roles, String unitId) {
        if (roles == null || roles.stream().noneMatch(
                r -> RoleCatalog.principalTypeOf(r) == RoleCatalog.PrincipalType.CUSTOMER)) {
            throw new BizException(1403, "仅使用单位可访问门户记录");
        }
        if (unitId == null || unitId.isEmpty()) {
            throw new BizException(1403, "缺少单位锚点");
        }
    }

    /** 门禁链③：记录归属——P1 仍按电梯当前 useUnitId（unitOf），P2 改记录快照列；越权 1403 无副作用 */
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

    /**
     * 查看门禁（docs/14 §4.6 可见性表，T4-C2）：客户只读可见集合 =
     * PENDING_CLIENT_SIGNATURE（可签）/ SIGNED / ARCHIVED / REFUSED_RETURNED（只读）；
     * PENDING_REVIEW / PENDING_CLIENT_SIGN / NULL / 其他一律不可见（422），历史版本仅内部/审计可见。
     * 与签字门禁分离：可见 ≠ 可签，签字一律走 requireSignable。
     */
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

    /** docs/14 §4.6 规则 1：最新版本 = 不存在 prev_version_id = 本记录 id 的后续版本（历史版本仅内部/审计可见） */
    private boolean isLatestVersion(MaintainRecord r) {
        Long next = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getPrevVersionId, r.id));
        return next == null || next == 0;
    }

    private String unitOf(MaintainRecord r) {
        com.cqwlw.maintenance.entity.Elevator el = elevatorMapper.selectOne(
                new LambdaQueryWrapper<com.cqwlw.maintenance.entity.Elevator>()
                        .eq(com.cqwlw.maintenance.entity.Elevator::getElevatorCode, r.elevatorCode).last("LIMIT 1"));
        return el == null || el.useUnitId == null ? "" : el.useUnitId;
    }

    public MaintainRecord findOr404(String id) {
        MaintainRecord r = recordMapper.selectById(id);
        if (r == null) {
            throw new BizException(1404, "维保记录不存在");
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
        // 已确认记录不可被二次提交覆盖签字/满意度（与 confirmByToken 一致）：
        // docs/05 §2.9 冻结与不可变、§10.4 #13「确认后状态不可重复提交」
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
        MaintainRecord r = findOr404(id);
        if (token == null || token.isEmpty() || !token.equals(r.shareToken)) {
            throw new BizException(401, "确认链接无效或已失效");
        }
        List<Map<String, Object>> items = resolveItems(r.itemsJson);
        List<String> photos = resolveRecordPhotos(items, r.photosJson);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("confirmed", "CONFIRMED".equals(r.confirmStatus));
        m.put("elevatorName", WorkOrderService.nz(r.elevatorName));
        m.put("elevatorCode", WorkOrderService.nz(r.elevatorCode));
        m.put("workType", WorkOrderService.nz(r.workType));
        m.put("workerName", WorkOrderService.nz(r.workerName));
        m.put("assistantName", WorkOrderService.nz(r.assistantName));
        m.put("checkinTime", TimeUtil.format(r.checkinTime));
        m.put("checkoutTime", TimeUtil.format(r.checkoutTime));
        m.put("duration", WorkOrderService.nz(r.duration));
        m.put("itemTotal", items == null ? 0 : items.size());
        m.put("photoCount", photos.size());
        // T4-I3：satisfaction 不再返回（docs/14 §4.7 评价关闭，签字链路不带满意度）
        m.put("signatureUrl", fileStorageService.resolveStoredUrl(r.signatureUrl));
        return m;
    }

    /**
     * 匿名分享链接签字（P1/P2 兼容期，docs/14 §4.6 规则 8）：token 即凭证（401 先于状态披露）；
     * T4-C1：状态/版本门禁与条件更新与 confirmAs 完全一致——此前无 lifecycle 门禁且 updateById
     * 读-改-写非原子，持链接可对 PENDING_REVIEW/PENDING_CLIENT_SIGN/NULL 记录签字（虚假签字合规绕过），
     * 并发 token 后写还会覆盖首签。已签/旧版本一律 422（先签为准），不再回 already 幂等体。
     */
    public Map<String, Object> confirmByToken(String id, String token, Map<String, Object> body) {
        MaintainRecord r = findOr404(id);
        if (token == null || token.isEmpty() || !token.equals(r.shareToken)) {
            throw new BizException(401, "确认链接无效或已失效");
        }
        requireSignable(r);
        return signRecord(r, body);
    }

    /** 内部/管理端全量投影（含上报与预览上下文）：客户路径禁用，一律走 toClientMap（P1 T4，docs/14 §4.7） */
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
        m.put("satisfaction", r.satisfaction);
        m.put("signatureFileId", r.signatureFileId);
        m.put("signatureUrl", fileStorageService.resolveStoredUrl(r.signatureUrl));
        m.put("shareToken", r.shareToken);
        m.put("reportPayload", r.reportPayloadJson == null ? null : JsonUtil.readMap(r.reportPayloadJson));
        // 预览上下文（docs/03 §六）：地址/使用单位/维保单位/手机号/平台ID/签到经纬度/条目总数
        m.put("context", r.previewContextJson == null ? Map.of() : JsonUtil.readMap(r.previewContextJson));
        return m;
    }

    /**
     * 客户字段白名单投影（P1 T4，docs/14 §4.7，只取现有列）：仅返回客户签署确认所需内容。
     * 明确不返回：reportStatus/uploadStatus/retryCount/reportPayload/context(previewContext)/shareToken/
     * originalRecordId/workerPlatformId/assistantPlatformId/refuseReason（内部复核退回）/satisfaction/幂等键/平台日志。
     * DC5：P1 无 client_refuse_reason 独立列（P3 增），客户拒签原因暂不回显，避免与内部 refuseReason 混回。
     */
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

    /**
     * 记录详情是长期读路径：照片优先按 photoFileIds 重签为当前可访问 URL，
     * 历史快照里的本地临时路径（wxfile://、http://tmp/）不可跨设备访问，必须过滤掉。
     * 2026-11-06 前的记录可能已成功上传 fileId、但 photos 仍冻结为本地路径，
     * 只重签 photos 会让其他手机与管理端继续显示断图（本轮修复）。
     */
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

    /**
     * 记录级照片墙与检查项明细同源：优先取已解析的检查项照片（可恢复旧记录的 fileId），
     * 仅当明细没有照片时才回退到冻结的 photos_json。
     */
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

    /** 上报状态归一（docs/04 A.3 P3 修订）：REPORTED→SUCCESS，FAILED→FAILED，其余 PENDING */
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
