package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.FaultMapper;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用单位门户（W3，Task 10，docs/14 §4）：数据域 = 本 useUnitId 名下的电梯，
 * 以及这些电梯的维保记录与急修单。列表在内存里再按电梯编码过滤一次——
 * 谓词写在查询条件上的同时保留这层防御，避免将来改查询时漏条件（AGENTS §3.1 越权无副作用）。
 * 报修复用急修链路（DirectoryService.createFault），并派给该梯责任人；无责任人留 OPEN 待调度。
 */
@Service
public class UnitPortalService {

    /** 使用单位报修的登记人前缀：员工端列表据此显示“使用单位报修”而不是陌生的 id */
    public static final String REPORTER_PREFIX = "unit:";
    /** 门户列表上限：先给固定窗口，分页等门户页落地后再补 */
    private static final int LIST_LIMIT = 50;
    /** 客户可见生命周期集合（docs/14 §4.6 可见性表，T4-I5）：待签 + 已签/归档/拒签三态只读；NULL/其他不可见 */
    private static final Set<String> CLIENT_VISIBLE_LIFECYCLES =
            Set.of("PENDING_CLIENT_SIGNATURE", "SIGNED", "ARCHIVED", "REFUSED_RETURNED");

    private final ElevatorMapper elevatorMapper;
    private final MaintainRecordMapper recordMapper;
    private final FaultMapper faultMapper;
    private final EmployeeMapper employeeMapper;
    private final DirectoryService directoryService;

    public UnitPortalService(ElevatorMapper elevatorMapper, MaintainRecordMapper recordMapper,
                             FaultMapper faultMapper, EmployeeMapper employeeMapper,
                             DirectoryService directoryService) {
        this.elevatorMapper = elevatorMapper;
        this.recordMapper = recordMapper;
        this.faultMapper = faultMapper;
        this.employeeMapper = employeeMapper;
        this.directoryService = directoryService;
    }

    /** 本单位电梯（停用的不展示） */
    public List<Map<String, Object>> elevators(String useUnitId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Elevator el : ownElevators(useUnitId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", el.id);
            m.put("elevatorCode", el.elevatorCode);
            m.put("elevatorName", el.elevatorName);
            m.put("projectName", el.projectName);
            m.put("location", el.location);
            m.put("insideNumber", el.insideNumber);
            m.put("regCode", el.regCode);
            m.put("deviceCode", el.deviceCode);
            m.put("nextMaintenanceDate", TimeUtil.formatDate(el.nextMaintenanceDate));
            m.put("nextCheckDate", TimeUtil.formatDate(el.nextCheckDate));
            out.add(m);
        }
        return out;
    }

    /**
     * 本单位维保记录（按签退时间倒序）。
     * T4-I5（docs/14 §4.6 可见性表）：客户只见 PENDING_CLIENT_SIGNATURE/SIGNED/ARCHIVED/REFUSED_RETURNED
     * 且为链末端最新版本——PENDING_REVIEW/PENDING_CLIENT_SIGN/NULL（历史）不外显；
     * 可见集合谓词统一在 visibleRecords()（查询条件 + 内存防御双保险）。
     * 行投影 recordRow 不含 shareToken（§4.7 白名单；兼容期例外仅 pendingSigns）。
     */
    public List<Map<String, Object>> records(String useUnitId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MaintainRecord r : visibleRecords(useUnitId)) {
            out.add(recordRow(r));
        }
        return out;
    }

    /**
     * 待签字（docs/14 §4.6）：仅唯一可签状态 PENDING_CLIENT_SIGNATURE + confirmStatus=PENDING
     * （本单位/可见集合/最新版本已由 visibleRecords() 保证）。
     * P1 兼容期（T7-C2/T8-A1，§4.6 规则 8）：仅此路径行内保留 shareToken——小程序待办「去签字」
     * 以行内 token 拼签字链接（sign 页只有 token 路径、无会话回退，去掉即断签字链路）；
     * records 列表与记录详情白名单仍排除（§4.7）。与 mock clientPendingSignRow 口径对齐。
     */
    public List<Map<String, Object>> pendingSigns(String useUnitId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MaintainRecord r : visibleRecords(useUnitId)) {
            String confirm = r.confirmStatus == null ? "PENDING" : r.confirmStatus;
            if (!"PENDING_CLIENT_SIGNATURE".equals(r.lifecycleStatus) || !"PENDING".equals(confirm)) {
                continue;
            }
            Map<String, Object> row = recordRow(r);
            row.put("shareToken", r.shareToken == null ? "" : r.shareToken);
            out.add(row);
        }
        return out;
    }

    /** 客户可见记录实体集：本单位电梯 + §4.6 可见集合 + 链末端最新版本，签退时间倒序（谓词进查询 + 内存防御双保险） */
    private List<MaintainRecord> visibleRecords(String useUnitId) {
        Set<String> codes = ownCodes(useUnitId);
        if (codes.isEmpty()) {
            return List.of();
        }
        List<MaintainRecord> list = recordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                .in(MaintainRecord::getElevatorCode, codes)
                .in(MaintainRecord::getLifecycleStatus, CLIENT_VISIBLE_LIFECYCLES)
                .orderByDesc(MaintainRecord::getCheckoutTime)
                .last("LIMIT " + LIST_LIMIT));
        List<MaintainRecord> out = new ArrayList<>();
        for (MaintainRecord r : list == null ? List.<MaintainRecord>of() : list) {
            if (!codes.contains(r.elevatorCode)) {
                continue;
            }
            if (r.lifecycleStatus == null || !CLIENT_VISIBLE_LIFECYCLES.contains(r.lifecycleStatus)) {
                continue;
            }
            if (!isLatestVersion(r)) {
                continue; // §4.6 规则 1：历史版本仅内部/审计可见
            }
            out.add(r);
        }
        return out;
    }

    /** docs/14 §4.6 规则 1：最新版本 = 不存在 prev_version_id = 本记录 id 的后续版本 */
    private boolean isLatestVersion(MaintainRecord r) {
        Long next = recordMapper.selectCount(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getPrevVersionId, r.id));
        return next == null || next == 0;
    }

    /** 本单位急修单（按报修时间倒序） */
    public List<Map<String, Object>> faults(String useUnitId) {
        Set<String> codes = ownCodes(useUnitId);
        if (codes.isEmpty()) {
            return List.of();
        }
        List<Fault> list = faultMapper.selectList(new LambdaQueryWrapper<Fault>()
                .in(Fault::getElevatorCode, codes)
                .orderByDesc(Fault::getCreatedAt)
                .last("LIMIT " + LIST_LIMIT));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Fault f : list == null ? List.<Fault>of() : list) {
            if (!codes.contains(f.elevatorCode)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id);
            m.put("faultNo", f.faultNo);
            m.put("elevatorCode", f.elevatorCode);
            m.put("faultType", f.faultType);
            m.put("desc", f.descr);
            m.put("status", f.status);
            m.put("createdAt", TimeUtil.format(f.createdAt));
            m.put("finishedAt", TimeUtil.format(f.finishedAt));
            m.put("result", f.handleDesc);
            out.add(m);
        }
        return out;
    }

    /**
     * 使用单位报修：电梯必须属本单位（越权 1403 且不落单），复用急修链路建单后派给该梯责任人。
     * 登记人记为 unit:{useUnitId}，员工端展示为“使用单位报修”（DirectoryService.enrichFaultViews）。
     */
    public Map<String, Object> reportFault(String useUnitId, Map<String, Object> body) {
        String code = body == null ? null : str(body, "elevatorCode");
        if (code == null || code.isBlank()) {
            throw new BizException(422, "请选择电梯");
        }
        String desc = str(body, "desc");
        if (desc == null || desc.isBlank()) {
            throw new BizException(422, "请填写故障描述");
        }
        Elevator target = ownElevators(useUnitId).stream()
                .filter(el -> code.equals(el.elevatorCode))
                .findFirst().orElse(null);
        if (target == null) {
            throw new BizException(1403, "仅可为本单位电梯报修");
        }
        Map<String, Object> payload = new LinkedHashMap<>(body);
        payload.put("elevatorCode", code);
        payload.put("desc", desc);
        Map<String, Object> view = directoryService.createFault(payload, REPORTER_PREFIX + useUnitId);
        String faultId = view.get("id") == null ? null : String.valueOf(view.get("id"));
        Employee worker = responsibleWorker(target);
        if (faultId == null || worker == null) {
            return view; // 无责任人：留 OPEN 待管理端派单
        }
        return directoryService.dispatchFault(faultId, worker.id);
    }

    /** 该梯责任人：workerPlatformId → sys_employee（派单口径，见 docs/14 §4） */
    private Employee responsibleWorker(Elevator el) {
        if (el.workerPlatformId == null || el.workerPlatformId.isBlank()) {
            return null;
        }
        List<Employee> list = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getPlatformId, el.workerPlatformId));
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private List<Elevator> ownElevators(String useUnitId) {
        if (useUnitId == null || useUnitId.isBlank()) {
            return List.of();
        }
        List<Elevator> list = elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getUseUnitId, useUnitId));
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .filter(el -> !"INACTIVE".equals(el.status))
                .filter(el -> useUnitId.equals(el.useUnitId))
                .sorted(Comparator.comparing(el -> el.id == null ? "" : el.id))
                .toList();
    }

    private Set<String> ownCodes(String useUnitId) {
        Set<String> codes = new LinkedHashSet<>();
        for (Elevator el : ownElevators(useUnitId)) {
            if (el.elevatorCode != null) {
                codes.add(el.elevatorCode);
            }
        }
        return codes;
    }

    /** 列表行投影（客户安全字段）：T4-I5 增 lifecycleStatus/version 供前端区分 待签/只读 与版本链 */
    private static Map<String, Object> recordRow(MaintainRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("elevatorCode", r.elevatorCode);
        m.put("elevatorName", r.elevatorName);
        m.put("workType", r.workType);
        m.put("workerName", r.workerName);
        m.put("assistantName", r.assistantName);
        m.put("checkinTime", TimeUtil.format(r.checkinTime));
        m.put("checkoutTime", TimeUtil.format(r.checkoutTime));
        m.put("duration", r.duration);
        m.put("confirmStatus", r.confirmStatus == null ? "PENDING" : r.confirmStatus);
        m.put("lifecycleStatus", r.lifecycleStatus);
        m.put("version", r.version);
        return m;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
