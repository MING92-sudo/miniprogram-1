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
import com.cqwlw.maintenance.util.RecordVersionUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UnitPortalService {

    /** 使用单位报修的登记人前缀：员工端列表据此显示“使用单位报修”而不是陌生的 id */
    public static final String REPORTER_PREFIX = "unit:";
    /** 门户列表上限：先给固定窗口，分页等门户页落地后再补 */
    private static final int LIST_LIMIT = 50;
    /** 客户可见生命周期集合：待签 + 已签/归档/拒签三态只读； */
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

    private ClientSignSlaService clientSignSlaService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientSignSlaService(ClientSignSlaService clientSignSlaService) {
        this.clientSignSlaService = clientSignSlaService;
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

    public List<Map<String, Object>> records(String useUnitId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MaintainRecord r : visibleRecords(useUnitId)) {
            out.add(recordRow(r));
        }
        return out;
    }

    /** 待签字：仅唯一可签状态 PENDING_CLIENT_SIGNATURE + confirmStatus=PENDING（本单位/可见集合/最新版本已由 visibleRecords() 保证）。 */
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

    /** 客户可见记录实体集：记录快照归属本单位 + §4.6 可见集合 + 链末端最新版本，签退时间倒序（谓词进查询 + 内存防御双保险） */
    private List<MaintainRecord> visibleRecords(String useUnitId) {
        // 空/缺单位锚点必须先短路：MyBatis-Plus 的 eq(null) 会忽略条件退化为全表，且下方 equals 会 NPE
        if (useUnitId == null || useUnitId.isBlank()) {
            return List.of();
        }
        List<MaintainRecord> list = recordMapper.selectList(new LambdaQueryWrapper<MaintainRecord>()
                .eq(MaintainRecord::getUseUnitId, useUnitId)
                .in(MaintainRecord::getLifecycleStatus, CLIENT_VISIBLE_LIFECYCLES)
                .orderByDesc(MaintainRecord::getCheckoutTime)
                .last("LIMIT " + LIST_LIMIT));
        List<MaintainRecord> candidates = new ArrayList<>();
        for (MaintainRecord r : list == null ? List.<MaintainRecord>of() : list) {
            if (!useUnitId.equals(r.useUnitId)) {
                continue;
            }
            if (r.lifecycleStatus == null || !CLIENT_VISIBLE_LIFECYCLES.contains(r.lifecycleStatus)) {
                continue;
            }
            candidates.add(r);
        }
        List<String> ids = new ArrayList<>();
        for (MaintainRecord r : candidates) {
            ids.add(r.id);
        }
        Set<String> superseded = RecordVersionUtil.supersededIds(recordMapper, ids);
        List<MaintainRecord> out = new ArrayList<>();
        for (MaintainRecord r : candidates) {
            if (superseded.contains(r.id)) {
                continue; // §4.6 规则 1：历史版本仅内部/审计可见
            }
            out.add(r);
        }
        return out;
    }

    public List<Map<String, Object>> faults(String useUnitId) {
        if (useUnitId == null || useUnitId.isBlank()) {
            return List.of(); // 空锚点短路：eq(null) 会退化为全表，且下方 equals 会 NPE
        }
        // 电梯列表仍按当前归属（spec §5）；此处 own 只用于电梯名 map，不再参与急修单归属判定
        List<Elevator> own = ownElevators(useUnitId);
        List<Fault> list = faultMapper.selectList(new LambdaQueryWrapper<Fault>()
                .eq(Fault::getUseUnitId, useUnitId)
                .orderByDesc(Fault::getCreatedAt)
                .last("LIMIT " + LIST_LIMIT));
        if (list == null || list.isEmpty()) {
            return List.of(); // 空列表短路
        }
        // 电梯名 map 仅由本单位在保档案建成（他单位/停用同码电梯不进入，数据域较旧 codes 重查更严格）；
        Map<String, String> elevatorNames = new LinkedHashMap<>();
        for (Elevator el : own) {
            if (el.elevatorCode != null && el.elevatorName != null && !el.elevatorName.isBlank()) {
                elevatorNames.putIfAbsent(el.elevatorCode, el.elevatorName);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Fault f : list) {
            if (!useUnitId.equals(f.useUnitId)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id);
            m.put("faultNo", f.faultNo);
            m.put("elevatorCode", f.elevatorCode);
            String name = elevatorNames.get(f.elevatorCode);
            m.put("elevatorName", name == null || name.isBlank() ? f.elevatorCode : name);
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

    public Map<String, Object> faultDetail(String useUnitId, String faultId) {
        return directoryService.getClientFault(faultId, useUnitId);
    }

    public Map<String, Object> confirmFault(String faultId, Set<String> roles, String useUnitId,
                                            boolean pass, String reason) {
        return confirmFault(faultId, roles, useUnitId, pass, reason, null);
    }

    public Map<String, Object> confirmFault(String faultId, Set<String> roles, String useUnitId,
                                            boolean pass, String reason, String signature) {
        return directoryService.confirmFault(faultId, roles, useUnitId, pass, reason, signature);
    }

    public Map<String, Object> cancelFault(String faultId, Set<String> roles, String useUnitId, String reason) {
        return directoryService.cancelFault(faultId, roles, reason, null, useUnitId);
    }

    /** 使用单位报修：电梯必须属本单位（越权 1403 且不落单），复用急修链路建单后派给该梯责任人。 */
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

    private Map<String, Object> recordRow(MaintainRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("elevatorCode", r.elevatorCode);
        m.put("elevatorName", r.elevatorName);
        Elevator el = elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, r.elevatorCode).last("LIMIT 1"));
        m.put("projectName", el == null || el.projectName == null ? "" : el.projectName);
        m.put("workType", r.workType);
        m.put("workerName", r.workerName);
        m.put("assistantName", r.assistantName);
        m.put("checkinTime", TimeUtil.format(r.checkinTime));
        m.put("checkoutTime", TimeUtil.format(r.checkoutTime));
        m.put("duration", r.duration);
        m.put("confirmStatus", r.confirmStatus == null ? "PENDING" : r.confirmStatus);
        m.put("lifecycleStatus", r.lifecycleStatus);
        m.put("version", r.version);
        if (clientSignSlaService != null) {
            java.time.LocalDateTime base = clientSignSlaService.baseOf(r);
            java.time.LocalDateTime deadline = clientSignSlaService.deadlineOf(r);
            m.put("signRequestedAt", TimeUtil.format(base));
            m.put("signDeadline", deadline == null ? "" : TimeUtil.format(deadline));
            m.put("overdue", deadline != null && TimeUtil.now().isAfter(deadline));
        }
        return m;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
