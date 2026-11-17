package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * 记录生命周期（M1，规格 §4.1 / 蓝图 §6.2，裁决 R1）：叠加在签退之后。
 * 签退即冻结快照 + 2.6 即报不变（D2）；本服务不注入任何平台上报服务——结构上保证归档/修订/拒签不触发平台调用。
 */
@Service
public class RecordLifecycleService {

    private final MaintainRecordMapper recordMapper;
    private final WorkOrderMapper orderMapper;
    private final EmployeeMapper employeeMapper;
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final RecordPdfService pdfService;
    private final FileStorageService fileStorage;
    private final SysConfigMapper configMapper;

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
        if (roles.stream().noneMatch(r -> "MAINT_SUP".equals(r) || "MAINT_MGR".equals(r))) {
            throw new BizException(1403, "无复核权限");
        }
        MaintainRecord r = findOr404(recordId);
        if (!"PENDING_REVIEW".equals(r.lifecycleStatus)) {
            throw new BizException(422, "记录不在待复核状态");
        }
        if (pass) {
            r.lifecycleStatus = "PENDING_CLIENT_SIGN";
        } else {
            if (reason == null || reason.trim().isEmpty()) {
                throw new BizException(422, "退回原因必填");
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
        recordMapper.updateById(r);
        return Map.of("ok", true, "lifecycleStatus", r.lifecycleStatus == null ? "" : r.lifecycleStatus);
    }

    /** 发起使用单位签字（主检/主管）：门控双签齐（或存量单人豁免） */
    public Map<String, Object> signRequest(String recordId, Set<String> roles) {
        if (roles.stream().noneMatch(r -> "MAINT_TECH".equals(r) || "MAINT_SUP".equals(r))) {
            throw new BizException(1403, "无发起签署权限");
        }
        MaintainRecord r = findOr404(recordId);
        if (!"PENDING_CLIENT_SIGN".equals(r.lifecycleStatus)) {
            throw new BizException(422, "记录不在待使用单位签字状态");
        }
        boolean dual = (notBlank(r.workerSignatureUrl) && notBlank(r.assistantSignatureUrl))
                || Integer.valueOf(1).equals(r.soloExempt);
        if (!dual) {
            throw new BizException(422, "双人签字未完成，不能发起使用单位签字");
        }
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
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
        o.assistantSignatureFileId = null;
        o.assistantSignatureAt = null;
        orderMapper.updateById(o);
        return Map.of("ok", true);
    }

    /**
     * 使用单位拒签（凭分享 token）：原因必填，退回维保人员重新走签字链路。
     * T4-I4（docs/14 §4.6 规则 2/5）：补全门禁链 confirmStatus=PENDING + 最新版本，
     * 落库改条件更新（WHERE lifecycle='PENDING_CLIENT_SIGNATURE' AND confirm_status='PENDING'）——
     * 旧 updateById 整实体读-改-写与 confirmAs 并发可把已落库 CONFIRMED 覆盖回 PENDING（状态撕裂），
     * 归档异常残留 CONFIRMED+PENDING_CLIENT_SIGNATURE 时也可能拒签退回已签记录；受影响行 0 → 422。
     * 不注入上报服务，结构保证拒签不触发平台 2.6。
     */
    public Map<String, Object> refuseByToken(String recordId, String token, String reason) {
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
        if (reason == null || reason.trim().isEmpty()) {
            throw new BizException(422, "拒绝原因必填");
        }
        int affected = recordMapper.update(null, new LambdaUpdateWrapper<MaintainRecord>()
                .eq(MaintainRecord::getId, r.id)
                .eq(MaintainRecord::getLifecycleStatus, "PENDING_CLIENT_SIGNATURE")
                .eq(MaintainRecord::getConfirmStatus, "PENDING")
                .set(MaintainRecord::getLifecycleStatus, "REFUSED_RETURNED")
                .set(MaintainRecord::getRefuseReason, reason));
        if (affected == 0) {
            throw new BizException(422, "记录状态已变更（可能已被签署），拒签未生效");
        }
        r.lifecycleStatus = "REFUSED_RETURNED";
        r.refuseReason = reason;
        return Map.of("ok", true);
    }

    /** 使用单位签署完成 → SIGNED → 自动归档（PDF + SHA-256）；不触发平台调用 */
    public void onClientSigned(MaintainRecord r) {
        r.lifecycleStatus = "SIGNED";
        archive(r);
    }

    /** 归档投影：存量 SIGNED 按需补归档（幂等）；非归档态 422 */
    public Map<String, Object> archiveView(String recordId) {
        MaintainRecord r = findOr404(recordId);
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

    private void archive(MaintainRecord r) {
        Elevator el = elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, r.elevatorCode).last("LIMIT 1"));
        UseUnit uu = el == null || el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        byte[] pdf = pdfService.render(r, el, uu);
        AppFile f = fileStorage.storeBytes(pdf, "record-" + r.id + ".pdf", "application/pdf", null);
        r.archiveFileId = f.id;
        r.archiveHash = sha256(pdf);
        r.archivedAt = TimeUtil.now();
        r.lifecycleStatus = "ARCHIVED";
        recordMapper.updateById(r);
    }

    private MaintainRecord findOr404(String id) {
        MaintainRecord r = recordMapper.selectById(id);
        if (r == null) {
            throw new BizException(1404, "维保记录不存在");
        }
        return r;
    }

    /**
     * C-25⑧ 匿名 token 端点归一（refuseByToken）：shareToken 校验依赖已载记录，
     * 无法「先验 token 后查库」，故记录不存在时抛与错 token 完全一致的 401 文案——
     * 匿名探测者无法据 1404/401 区分记录 id 是否存在（消除存在性 oracle）。
     * 内部认证路径（review/signRequest/archiveView）仍走 findOr404（1404），不归一。
     */
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