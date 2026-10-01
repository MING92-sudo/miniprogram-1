package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    public UnitRecordService(MaintainRecordMapper recordMapper, WorkOrderService workOrderService,
                             FileStorageService fileStorageService) {
        this.recordMapper = recordMapper;
        this.workOrderService = workOrderService;
        this.fileStorageService = fileStorageService;
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

    public Map<String, Object> confirm(String id, Map<String, Object> body) {
        MaintainRecord r = findOr404(id);
        if ("CONFIRMED".equals(r.confirmStatus)) {
            return JsonUtil.map("ok", true, "already", true);
        }
        String fileId = WorkOrderService.strOrEmpty(body.get("signatureFileId"));
        String url = requireSignatureUrl(fileId);
        r.confirmStatus = "CONFIRMED";
        r.satisfaction = intOf(body.get("satisfaction"));
        r.signatureFileId = fileId;
        r.signatureUrl = url;
        recordMapper.updateById(r);
        return JsonUtil.map("ok", true);
    }

    public Map<String, Object> signView(String id, String token) {
        MaintainRecord r = findOr404(id);
        if (token == null || token.isEmpty() || !token.equals(r.shareToken)) {
            throw new BizException(401, "确认链接无效或已失效");
        }
        List<Map<String, Object>> items = JsonUtil.readList(r.itemsJson);
        List<String> photos = JsonUtil.readStringList(r.photosJson);
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
        m.put("satisfaction", r.satisfaction);
        m.put("signatureUrl", WorkOrderService.nz(r.signatureUrl));
        return m;
    }

    public Map<String, Object> confirmByToken(String id, String token, Map<String, Object> body) {
        MaintainRecord r = findOr404(id);
        if (token == null || token.isEmpty() || !token.equals(r.shareToken)) {
            throw new BizException(401, "确认链接无效或已失效");
        }
        if ("CONFIRMED".equals(r.confirmStatus)) {
            return JsonUtil.map("ok", true, "already", true);
        }
        // 必须有真实签名图：仅凭任意 URL 字符串即可把记录置为"使用单位已确认签字"，
        // 会使合规记录出现无签名却已确认的状态。URL 由服务端按 fileId 反查，客户端地址不采信。
        String fileId = WorkOrderService.strOrEmpty(body.get("signatureFileId"));
        String url = requireSignatureUrl(fileId);
        r.confirmStatus = "CONFIRMED";
        r.satisfaction = intOf(body.get("satisfaction"));
        r.signatureFileId = fileId;
        r.signatureUrl = url;
        recordMapper.updateById(r);
        return JsonUtil.map("ok", true);
    }

    /** 签名图必须上传成功且在本系统落库；返回服务端反查到的可访问 URL */
    private String requireSignatureUrl(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new BizException(422, "请先完成签名并上传");
        }
        String url = fileStorageService.resolveUrl(fileId);
        if (url == null || url.isBlank()) {
            throw new BizException(422, "签名图上传记录不存在，请重新上传");
        }
        return url;
    }

    private static int intOf(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return o == null ? 0 : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
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
        m.put("items", r.itemsJson == null ? List.of() : JsonUtil.readList(r.itemsJson));
        m.put("photos", JsonUtil.readStringList(r.photosJson));
        m.put("workerSignatureUrl", r.workerSignatureUrl);
        m.put("assistantSignatureUrl", r.assistantSignatureUrl);
        m.put("problemCodes", JsonUtil.readStringList(r.problemCodesJson));
        m.put("originalRecordId", r.originalRecordId);
        m.put("reportStatus", r.reportStatus);
        m.put("uploadStatus", uploadStatus(r.reportStatus));
        m.put("retryCount", r.retryCount);
        m.put("nextMaintenanceDate", TimeUtil.formatDate(r.nextMaintenanceDate));
        m.put("confirmStatus", r.confirmStatus);
        m.put("satisfaction", r.satisfaction);
        m.put("signatureFileId", r.signatureFileId);
        m.put("signatureUrl", r.signatureUrl);
        m.put("shareToken", r.shareToken);
        m.put("reportPayload", r.reportPayloadJson == null ? null : JsonUtil.readMap(r.reportPayloadJson));
        return m;
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
