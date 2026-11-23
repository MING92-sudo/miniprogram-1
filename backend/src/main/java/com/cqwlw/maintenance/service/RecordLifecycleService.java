package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.AppFile;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.SysConfig;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.SysConfigMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

@Service
public class RecordLifecycleService {

    public static final Map<String, String> REFUSE_CODES = Map.of(
            "R1", "记录内容与现场不符",
            "R2", "维保项目漏项或未按单执行",
            "R3", "现场照片不清晰或与实际不符",
            "R4", "时间或人员信息有误",
            "R5", "其他");

    private final MaintainRecordMapper recordMapper;
    private final WorkOrderMapper orderMapper;
    private final EmployeeMapper employeeMapper;
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final RecordPdfService pdfService;
    private final FileStorageService fileStorage;
    private final SysConfigMapper configMapper;
    private EmployeeScopeService scopeService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setScopeService(EmployeeScopeService scopeService) {
        this.scopeService = scopeService;
    }

    public RecordLifecycleService(MaintainRecordMapper recordMapper, WorkOrderMapper orderMapper,
                                  EmployeeMapper employeeMapper, ElevatorMapper elevatorMapper,
                                  UseUnitMapper useUnitMapper, RecordPdfService pdfService,
                                  FileStorageService fileStorage, SysConfigMapper configMapper) {
        this.recordMapper = recordMapper;
        this.orderMapper = orderMapper;
        this.employeeMapper = employeeMapper;
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.pdfService = pdfService;
        this.fileStorage = fileStorage;
        this.configMapper = configMapper;
    }

    /** 签退创建记录时的初始生命周期：复核开关（sys_config record_review_enabled，默认关=免复核直达） */
    public String initialStatus() {
        SysConfig c = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getCfgKey, "record_review_enabled").last("LIMIT 1"));
        return c != null && "1".equals(c.cfgValue) ? "PENDING_REVIEW" : "PENDING_CLIENT_SIGN";
    }

    /** 主管复核（管理端动作，小程序仅展示状态）：通过→待使用单位签字；退回→作业中（原因必填） */
    public Map<String, Object> review(String recordId, boolean pass, String reason, Set<String> roles) {
        return review(recordId, pass, reason, roles, null);
    }

    public Map<String, Object> review(String recordId, boolean pass, String reason, Set<String> roles, String empId) {
        if (roles.stream().noneMatch(r -> PermissionMatrix.allowsNew(r, PermissionMatrix.RECORD_REVIEW))) {
            throw new BizException(1403, "无复核权限");
        }
        MaintainRecord r = findOr404(recordId);
        requireRecordScope(r, empId);
        if (!"PENDING_REVIEW".equals(r.lifecycleStatus)) {
            throw new BizException(422, "记录不在待复核状态");
        }
        if (pass) {
            r.lifecycleStatus = "PENDING_CLIENT_SIGN";
            recordMapper.updateById(r);
        } else {
            if (reason == null || reason.trim().isEmpty()) {
                throw new BizException(422, "退回原因必填");
            }
            LambdaUpdateWrapper<MaintainRecord> returned = new LambdaUpdateWrapper<MaintainRecord>()
                    .eq(MaintainRecord::getId, r.id)
                    .eq(MaintainRecord::getLifecycleStatus, "PENDING_REVIEW")
                    .set(MaintainRecord::getLifecycleStatus, null)
                    .set(MaintainRecord::getRefuseReason, reason);
            if (recordMapper.update(null, returned) != 1) {
                throw new BizException(422, "记录状态已变更，请刷新后重试");
            }
            r.lifecycleStatus = null;
            r.refuseReason = reason;
            if (r.workOrderId != null) {
                WorkOrder o = orderMapper.selectById(r.workOrderId);
                if (o != null && "DONE".equals(o.status)) {
                    o.status = "PROCESSING";
                    orderMapper.updateById(o);
                }
            }
        }
        return Map.of("ok", true, "lifecycleStatus", r.lifecycleStatus == null ? "" : r.lifecycleStatus);
    }

    /** 发起使用单位签字（主检/主管）：门控双签齐（或存量单人豁免） */
    public Map<String, Object> signRequest(String recordId, Set<String> roles) {
        return signRequest(recordId, roles, null);
    }

    public Map<String, Object> signRequest(String recordId, Set<String> roles, String empId) {
        if (roles.stream().noneMatch(r -> PermissionMatrix.allowsNew(r, PermissionMatrix.RECORD_SIGN_REQUEST))) {
            throw new BizException(1403, "无发起签署权限");
        }
        MaintainRecord r = findOr404(recordId);
        requireRecordScope(r, empId);
        if (!"PENDING_CLIENT_SIGN".equals(r.lifecycleStatus)) {
            throw new BizException(422, "记录不在待使用单位签字状态");
        }
        boolean dual = (notBlank(r.workerSignatureUrl) && notBlank(r.assistantSignatureUrl))
                || Integer.valueOf(1).equals(r.soloExempt);
        if (!dual) {
            throw new BizException(422, "双人签字未完成，不能发起使用单位签字");
        }
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.signRequestedAt = TimeUtil.now();
        r.signRemindedAt = null;
        recordMapper.updateById(r);
        return Map.of("ok", true, "shareToken", r.shareToken == null ? "" : r.shareToken);
    }

    /** 配合人退回签字（工单级，签退前）：仅配合人本人；清签字留痕；无上报副作用 */
    public Map<String, Object> signatureRefuse(String orderId, String empId) {
        WorkOrder o = orderMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(1404, "工单不存在");
        }
        Employee me = employeeMapper.selectById(empId);
        if (me == null) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        if (me.platformId == null || !me.platformId.equals(o.assistantPlatformId)) {
            throw new BizException(1403, "仅配合人员可退回签字");
        }
        if (!"PROCESSING".equals(o.status)) {
            throw new BizException(422, "工单不在作业中状态");
        }
        LambdaUpdateWrapper<WorkOrder> clearSignature = new LambdaUpdateWrapper<>();
        clearSignature.eq(WorkOrder::getId, orderId)
                .eq(WorkOrder::getStatus, "PROCESSING")
                .eq(WorkOrder::getAssistantPlatformId, o.assistantPlatformId)
                .set(WorkOrder::getAssistantSignatureFileId, null)
                .set(WorkOrder::getAssistantSignatureAt, null);
        if (orderMapper.update(null, clearSignature) != 1) {
            throw new BizException(1003, "配合人员已变更，请刷新后重试");
        }
        o.assistantSignatureFileId = null;
        o.assistantSignatureAt = null;
        return Map.of("ok", true);
    }

    /** 使用单位拒签（凭分享 token）：原因必填，退回维保人员重新走签字链路。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> refuseByToken(String recordId, String token, String reason) {
        return refuseByToken(recordId, token, null, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> refuseByToken(String recordId, String token, String refuseCode, String reason) {
        MaintainRecord r = findOr401(recordId); // C-25⑧：不存在 → 401 同错 token 文案（匿名端点无存在性 oracle）
        if (token == null || token.isEmpty() || !token.equals(r.shareToken)) {
            throw new BizException(401, "确认链接无效或已失效");
        }
        if (!"PENDING_CLIENT_SIGNATURE".equals(r.lifecycleStatus)) {
            throw new BizException(422, "记录不在待使用单位签署状态");
        }
        if (!"PENDING".equals(r.confirmStatus)) {
            throw new BizException(422, "记录已签署，不能拒签");
        }
        Long next = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getPrevVersionId, r.id));
        if (next != null && next > 0) {
            throw new BizException(422, "记录已被新版本替代，请处理最新记录");
        }
        // 旧调用方（未传码）按兼容期回退——R5+说明 等价于原「原因必填」
        String code = refuseCode == null || refuseCode.isBlank() ? "" : refuseCode.trim().toUpperCase();
        String detail = reason == null ? "" : reason.trim();
        if (code.isEmpty()) {
            if (detail.isEmpty()) {
                throw new BizException(422, "拒绝原因必填");
            }
        } else {
            if (!REFUSE_CODES.containsKey(code)) {
                throw new BizException(422, "拒签原因分类码无效（R1—R5）");
            }
            if ("R5".equals(code) && detail.isEmpty()) {
                throw new BizException(422, "选择「其他」时必须填写拒签说明");
            }
        }
        String label = code.isEmpty() ? "" : REFUSE_CODES.get(code);
        String composed = label.isEmpty() ? detail
                : detail.isEmpty() ? label : label + "：" + detail;
        int affected = recordMapper.update(null, new LambdaUpdateWrapper<MaintainRecord>()
                .eq(MaintainRecord::getId, r.id)
                .eq(MaintainRecord::getLifecycleStatus, "PENDING_CLIENT_SIGNATURE")
                .eq(MaintainRecord::getConfirmStatus, "PENDING")
                .set(MaintainRecord::getLifecycleStatus, "REFUSED_RETURNED")
                .set(MaintainRecord::getRefuseReason, composed)
                .set(MaintainRecord::getRefuseCode, code.isEmpty() ? null : code));
        if (affected == 0) {
            throw new BizException(422, "记录状态已变更（可能已被签署），拒签未生效");
        }
        reopenForRework(r);
        r.lifecycleStatus = "REFUSED_RETURNED";
        r.refuseReason = composed;
        r.refuseCode = code.isEmpty() ? null : code;
        return Map.of("ok", true);
    }

    private void reopenForRework(MaintainRecord r) {
        if (r.workOrderId == null || r.workOrderId.isBlank()) {
            return;
        }
        WorkOrder o = orderMapper.selectById(r.workOrderId);
        if (o == null) {
            throw new BizException(1404, "来源工单不存在，无法退回整改");
        }
        LambdaUpdateWrapper<WorkOrder> reopen = new LambdaUpdateWrapper<WorkOrder>()
                .eq(WorkOrder::getId, o.id)
                .eq(WorkOrder::getStatus, "DONE")
                .set(WorkOrder::getStatus, "PROCESSING")
                .set(WorkOrder::getWorkerSignatureFileId, null)
                .set(WorkOrder::getWorkerSignatureAt, null)
                .set(WorkOrder::getAssistantSignatureFileId, null)
                .set(WorkOrder::getAssistantSignatureAt, null);
        if (orderMapper.update(null, reopen) != 1) {
            throw new BizException(1003, "工单状态已变更，无法退回整改");
        }
        o.status = "PROCESSING";
        o.workerSignatureFileId = null;
        o.workerSignatureAt = null;
        o.assistantSignatureFileId = null;
        o.assistantSignatureAt = null;
    }

    /** 使用单位签署完成 → SIGNED → 自动归档（PDF + SHA-256）；不触发平台调用 */
    public void onClientSigned(MaintainRecord r) {
        r.lifecycleStatus = "SIGNED";
        archive(r);
    }

    /** 归档投影：存量 SIGNED 按需补归档（幂等）；非归档态 422 */
    public Map<String, Object> archiveView(String recordId) {
        return archiveView(recordId, null);
    }

    public Map<String, Object> archiveView(String recordId, String empId) {
        MaintainRecord r = findOr404(recordId);
        requireRecordScope(r, empId);
        if ("SIGNED".equals(r.lifecycleStatus)) {
            archive(r);
        }
        if (!"ARCHIVED".equals(r.lifecycleStatus)) {
            throw new BizException(422, "记录尚未归档");
        }
        return Map.of("url", fileStorage.urlOf(r.archiveFileId),
                "hash", r.archiveHash == null ? "" : r.archiveHash,
                "archivedAt", TimeUtil.format(r.archivedAt));
    }

    private void requireRecordScope(MaintainRecord r, String empId) {
        if (scopeService != null && !scopeService.canReadRecord(scopeService.require(empId), r)) {
            throw new BizException(1403, "仅可查看本人或本班组记录");
        }
    }

    private void archive(MaintainRecord r) {
        Elevator el = elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, r.elevatorCode).last("LIMIT 1"));
        UseUnit uu = el == null || el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        // render 会把签名图重签为时效 URL（?s=）回填到 r 上供 PDF 取图——仅内存用途；
        byte[] pdf = pdfService.render(r, el, uu);
        AppFile f = fileStorage.storeBytes(pdf, "record-" + r.id + ".pdf", "application/pdf", null);
        java.time.LocalDateTime now = TimeUtil.now();
        String hash = sha256(pdf);
        recordMapper.update(null, new LambdaUpdateWrapper<MaintainRecord>()
                .eq(MaintainRecord::getId, r.id)
                .set(MaintainRecord::getArchiveFileId, f.id)
                .set(MaintainRecord::getArchiveHash, hash)
                .set(MaintainRecord::getArchivedAt, now)
                .set(MaintainRecord::getLifecycleStatus, "ARCHIVED"));
        r.archiveFileId = f.id;
        r.archiveHash = hash;
        r.archivedAt = now;
        r.lifecycleStatus = "ARCHIVED";
    }

    private MaintainRecord findOr404(String id) {
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

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}