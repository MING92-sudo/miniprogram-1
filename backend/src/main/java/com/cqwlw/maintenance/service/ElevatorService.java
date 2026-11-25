package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Company;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.UseUnit;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.CompanyMapper;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.UseUnitMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ElevatorService {

    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final EmployeeScopeService scopeService;
    private final WorkOrderMapper workOrderMapper;
    private final CompanyMapper companyMapper;

    public ElevatorService(ElevatorMapper elevatorMapper, UseUnitMapper useUnitMapper,
                           EmployeeScopeService scopeService, WorkOrderMapper workOrderMapper,
                           CompanyMapper companyMapper) {
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.scopeService = scopeService;
        this.workOrderMapper = workOrderMapper;
        this.companyMapper = companyMapper;
    }

    public Elevator get(String id) {
        return elevatorMapper.selectById(id);
    }

    public Elevator getByCode(String code) {
        return elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, code).last("LIMIT 1"));
    }

    public List<Map<String, Object>> listView(String empId) {
        // 电梯绑定字段（worker/assistant platformId）池化后不再写入，不作归属键。
        Employee e = scopeService.require(empId);
        Set<String> ids = scopeService.visibleReadPlatformIds(e);
        String maintUnitName = maintUnitName();
        return elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>().orderByAsc(Elevator::getId))
                .stream()
                .filter(el -> !"INACTIVE".equals(el.status))
                .filter(el -> ids == null || regionVisible(el, e))
                .map(el -> mapRow(el, maintUnitName)).toList();
    }

    /** 区域匹配键（读谓词）：电梯不限区域（regionId 空）放行，或与登录人同区域 */
    private static boolean regionVisible(Elevator el, Employee e) {
        if (el.regionId == null || el.regionId.isBlank()) {
            return true;
        }
        return el.regionId.equals(e.regionId);
    }

    /** 档案「所在区域」展示：setter 注入保持既有构造器签名（单测不传时返回空串） */
    private com.cqwlw.maintenance.mapper.RegionMapper regionMapper;
    /** overview 聚合（合同/急修统计）所需的可选依赖：缺失时对应区块留空，不拖垮档案 */
    private com.cqwlw.maintenance.mapper.FaultMapper faultMapper;
    private com.cqwlw.maintenance.mapper.ContractElevatorMapper contractElevatorMapper;
    private com.cqwlw.maintenance.mapper.MaintenanceContractMapper contractMapper;
    /** 联系人卡「配合人电话」按 platformId 反查员工手机号；缺失时该格留空 */
    private com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRegionMapper(com.cqwlw.maintenance.mapper.RegionMapper regionMapper) {
        this.regionMapper = regionMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFaultMapper(com.cqwlw.maintenance.mapper.FaultMapper faultMapper) {
        this.faultMapper = faultMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setContractElevatorMapper(
            com.cqwlw.maintenance.mapper.ContractElevatorMapper contractElevatorMapper) {
        this.contractElevatorMapper = contractElevatorMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setContractMapper(com.cqwlw.maintenance.mapper.MaintenanceContractMapper contractMapper) {
        this.contractMapper = contractMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEmployeeMapper(com.cqwlw.maintenance.mapper.EmployeeMapper employeeMapper) {
        this.employeeMapper = employeeMapper;
    }

    private String regionNameOf(String regionId) {
        if (regionId == null || regionId.isBlank() || regionMapper == null) {
            return "";
        }
        com.cqwlw.maintenance.entity.Region r = regionMapper.selectById(regionId);
        return r == null || r.name == null ? "" : r.name;
    }

    /** 写谓词（AGENTS §3.1 写绝不扩区域）：认领制下「本人负责」= 本人作为主维保/配合人持有该梯在途工单（status≠DONE，已签退 DONE 工单不再构成维护授权）。 */
    private boolean assignedToOpenOrder(String elevatorId, String platformId) {
        if (platformId == null || platformId.isBlank()) {
            return false;
        }
        Long c = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, elevatorId)
                .ne(WorkOrder::getStatus, "DONE")
                .and(w -> w.eq(WorkOrder::getWorkerPlatformId, platformId)
                        .or().eq(WorkOrder::getAssistantPlatformId, platformId)));
        return c != null && c > 0;
    }

    public List<Map<String, Object>> listViewForUnit(String unitId) {
        if (unitId == null || unitId.isBlank()) {
            return List.of();
        }
        String maintUnitName = maintUnitName();
        return elevatorMapper.selectList(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getUseUnitId, unitId).orderByAsc(Elevator::getId))
                .stream()
                .filter(el -> !"INACTIVE".equals(el.status))
                .map(el -> mapRow(el, maintUnitName)).toList();
    }

    private Map<String, Object> mapRow(Elevator el, String maintUnitName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", el.id);
        m.put("elevatorName", el.elevatorName);
        m.put("elevatorCode", el.elevatorCode);
        m.put("deviceCode", el.deviceCode);
        m.put("regCode", el.regCode);
        m.put("model", el.model);
        m.put("projectName", nz(el.projectName));
        m.put("nextMaintenanceDate", TimeUtil.formatDate(el.nextMaintenanceDate));
        m.put("maintUnitName", maintUnitName);
        return m;
    }

    /** 维保单位名：系统单维保单位（company 表首条，DirectoryService 同口径）；未配置时置空串 */
    private String maintUnitName() {
        List<Company> cs = companyMapper.selectList(null);
        if (cs == null || cs.isEmpty()) {
            return "";
        }
        Company c = cs.get(0);
        return c == null || c.name == null ? "" : c.name;
    }

    public Map<String, Object> viewByCode(String code) {
        Elevator el = getByCode(code == null ? "" : code.trim());
        if (el == null) {
            throw new BizException(1404, "电梯不存在，请核对编号");
        }
        if ("INACTIVE".equals(el.status)) {
            throw new BizException(422, "该电梯已停用，贴梯二维码失效（docs/09 §6.6）");
        }
        UseUnit uu = el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", el.id);
        m.put("elevatorCode", el.elevatorCode);
        m.put("elevatorName", el.elevatorName);
        m.put("projectName", nz(el.projectName));
        m.put("location", nz(el.location));
        m.put("regCode", nz(el.regCode));
        m.put("deviceCode", nz(el.deviceCode));
        m.put("model", nz(el.model));
        m.put("category", nz(el.category));
        m.put("useUnitName", uu == null ? "" : nz(uu.unitName));
        return m;
    }

    public Map<String, Object> profile(String id, String empId) {
        Elevator el = get(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        // 越权防护：非管理角色按区域匹配键（同区域或不限区域可读），防跨区域读取管理员/应急电话等敏感字段；
        Employee e = scopeService.require(empId);
        Set<String> ids = scopeService.visibleWorkerPlatformIds(e);
        if (ids != null && !regionVisible(el, e)) {
            throw new BizException(1403, "仅可查看本区域的电梯");
        }
        return profileView(el);
    }

    public Map<String, Object> profileForUnit(String useUnitId, String elevatorId) {
        if (useUnitId == null || useUnitId.isBlank()) {
            throw new BizException(1403, "当前账号无使用单位数据权限");
        }
        Elevator el = get(elevatorId);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        if (!useUnitId.equals(el.useUnitId)) {
            throw new BizException(1403, "无权查看其他使用单位电梯");
        }
        return profileView(el);
    }

    /** 档案投影（员工与客户共用，字段名与前端契约一致；客户可达性由 profileForUnit 本单位谓词把门） */
    private Map<String, Object> profileView(Elevator el) {
        UseUnit uu = el.useUnitId == null ? null : useUnitMapper.selectById(el.useUnitId);
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("syncedAt", TimeUtil.format(el.platformSyncedAt));
        platform.put("elevatorCode", nz(el.elevatorCode));
        platform.put("registrationCode", nz(el.regCode));
        platform.put("deviceCode", nz(el.deviceCode));
        platform.put("factoryNumber", nz(el.factoryNumber));
        platform.put("useUnitEntityId", nz(el.useUnitEntityId));
        platform.put("elevatorAdminister", nz(el.elevatorAdminister));
        // 梯级电话为空（未同步/平台脱敏未回填）时退到使用单位档案的同名联系人，避免档案页恒空
        platform.put("elevatorAdministerPhone", !nz(el.elevatorAdministerPhone).isEmpty()
                ? el.elevatorAdministerPhone : (uu == null ? "" : nz(uu.elevatorAdministerPhone)));
        platform.put("emergencyPhone", !nz(el.emergencyPhone).isEmpty()
                ? el.emergencyPhone : (uu == null ? "" : nz(uu.emergencyPhone)));

        Map<String, Object> local = new LinkedHashMap<>();
        local.put("projectName", nz(el.projectName));
        local.put("unitPrincipal", uu == null ? "" : nz(uu.unitPrincipal));
        local.put("regionName", regionNameOf(el.regionId));
        local.put("address", nz(el.location));
        local.put("lng", el.lng != null ? String.valueOf(el.lng) : "");
        local.put("lat", el.lat != null ? String.valueOf(el.lat) : "");
        local.put("brand", nz(el.brand));
        local.put("manufacturer", nz(el.manufacturer));
        local.put("factoryNumber", nz(el.factoryNumber));
        local.put("driveMode", nz(el.driveMode));
        local.put("ratedLoad", el.ratedLoad != null ? el.ratedLoad + (el.ratedLoadUnit == null ? "kg" : el.ratedLoadUnit) : "");
        local.put("ratedSpeed", el.ratedSpeed != null ? el.ratedSpeed + (el.ratedSpeedUnit == null ? "m/s" : el.ratedSpeedUnit) : "");
        local.put("stationsDoors", nz(el.stationsDoors));
        local.put("nextCheckDate", TimeUtil.formatDate(el.nextCheckDate));
        local.put("nextMaintenanceDate", TimeUtil.formatDate(el.nextMaintenanceDate));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("elevatorId", el.id);
        out.put("elevatorName", el.elevatorName);
        out.put("category", nz(el.category));
        out.put("insideNumber", nz(el.insideNumber));
        out.put("model", nz(el.model));
        out.put("platform", platform);
        out.put("local", local);
        out.put("overview", buildOverview(el, uu));
        return out;
    }

    /** 档案概览（小程序概览四格/服务记录/统计/联系人卡片）：可选依赖缺失时对应区块留空。 */
    private Map<String, Object> buildOverview(Elevator el, UseUnit uu) {
        List<WorkOrder> orders = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, el.id)
                .orderByDesc(WorkOrder::getPlanTime).last("LIMIT 20"));
        List<com.cqwlw.maintenance.entity.Fault> faults = faultMapper == null
                ? List.<com.cqwlw.maintenance.entity.Fault>of()
                : faultMapper.selectList(new LambdaQueryWrapper<com.cqwlw.maintenance.entity.Fault>()
                        .eq(com.cqwlw.maintenance.entity.Fault::getElevatorCode, el.elevatorCode)
                        .orderByDesc(com.cqwlw.maintenance.entity.Fault::getCreatedAt).last("LIMIT 50"));

        List<Map<String, Object>> log = new java.util.ArrayList<>();
        orders.stream().limit(4).forEach(o -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tone", "DONE".equals(o.status) ? "ok" : "amber");
            item.put("title", nz(o.workType) + " · " + nz(o.orderNo));
            item.put("sub", joinNonBlank(" · ", o.workerName,
                    "DONE".equals(o.status) ? "已完成" : "进行中"));
            item.put("date", o.checkoutTime != null ? TimeUtil.date(o.checkoutTime)
                    : (o.planTime == null ? "" : TimeUtil.date(o.planTime)));
            log.add(item);
        });
        faults.stream().limit(2).forEach(f -> {
            boolean rescue = (f.trappedCount != null && f.trappedCount > 0)
                    || (f.faultType != null && f.faultType.contains("困人"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tone", rescue ? "signal" : "amber");
            item.put("title", rescue ? "急修 · 困人救援" : "急修");
            item.put("sub", joinNonBlank(" · ", f.faultNo,
                    "CLOSED".equals(f.status) ? "已闭环" : "处置中"));
            item.put("date", f.createdAt == null ? "" : TimeUtil.date(f.createdAt));
            log.add(item);
        });

        java.time.LocalDateTime since30 = TimeUtil.now().minusDays(30);
        long fault30 = faults.stream()
                .filter(f -> f.createdAt != null && f.createdAt.isAfter(since30)).count();
        long avgResp = Math.round(faults.stream()
                .filter(f -> f.arrivedAt != null && f.createdAt != null)
                .mapToLong(f -> java.time.Duration.between(f.createdAt, f.arrivedAt).toMinutes())
                .average().orElse(0));
        long daysToCheck = el.nextCheckDate == null ? 0
                : Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(
                        TimeUtil.now().toLocalDate(), el.nextCheckDate));

        // 合同：contract_elevator 关联（可选依赖缺失时留空）
        String contractNo = "";
        String contractPeriod = "";
        if (contractElevatorMapper != null && contractMapper != null) {
            com.cqwlw.maintenance.entity.ContractElevator ce = contractElevatorMapper.selectOne(
                    new LambdaQueryWrapper<com.cqwlw.maintenance.entity.ContractElevator>()
                            .eq(com.cqwlw.maintenance.entity.ContractElevator::getElevatorId, el.id)
                            .last("LIMIT 1"));
            if (ce != null) {
                com.cqwlw.maintenance.entity.MaintenanceContract ct = contractMapper.selectById(ce.contractId);
                if (ct != null) {
                    contractNo = nz(ct.contractNo);
                    contractPeriod = ct.startDate + " ~ " + ct.endDate;
                }
            }
        }

        // 主/配合联系人取最近一次工单（认领制下电梯无固定绑定人）
        WorkOrder latest = orders.isEmpty() ? null : orders.get(0);
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("unit", uu == null ? "" : nz(uu.unitPrincipal));
        contact.put("unitPhone", uu == null ? "" : nz(uu.unitPrincipalPhone));
        contact.put("main", latest == null ? "" : nz(latest.workerName));
        // 「配合人电话」标签对应手机号：按配合人 platformId 反查员工档案；无配合人/未登记/缺依赖时留空，绝不回退姓名
        contact.put("assist", assistantPhoneOf(latest));
        contact.put("addr", nz(el.location));
        contact.put("contractNo", contractNo);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("fault30", fault30);
        stats.put("avgResp", avgResp);
        stats.put("daysToCheck", daysToCheck);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("useUnitName", uu == null ? "" : nz(uu.unitName));
        overview.put("contractNo", contractNo);
        overview.put("contractPeriod", contractPeriod);
        overview.put("nextService", TimeUtil.formatDate(el.nextMaintenanceDate));
        overview.put("inspection", TimeUtil.formatDate(el.nextCheckDate));
        overview.put("serviceLog", log);
        overview.put("stats", stats);
        overview.put("contact", contact);
        return overview;
    }

    /** 配合人电话：最近工单配合人 platformId 反查员工手机号；查不到一律空串（不回退姓名，防标签/数据错位） */
    private String assistantPhoneOf(WorkOrder latest) {
        if (latest == null || latest.assistantPlatformId == null || latest.assistantPlatformId.isBlank()
                || employeeMapper == null) {
            return "";
        }
        Employee assistant = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getPlatformId, latest.assistantPlatformId).last("LIMIT 1"));
        return assistant == null ? "" : nz(assistant.phone);
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(sep);
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** 越权 1403。 */
    public void updateProjectName(String id, String empId, String projectName) {
        Elevator el = get(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        Employee e = scopeService.require(empId);
        Set<String> ids = scopeService.visibleWorkerPlatformIds(e);
        if (ids != null && !assignedToOpenOrder(id, e.platformId)) {
            throw new BizException(1403, "仅可维护本人负责的电梯（需先在工单池认领该梯在途工单）");
        }
        String v = projectName == null ? "" : projectName.trim();
        if (v.isEmpty()) {
            throw new BizException(422, "项目名称不能为空");
        }
        Elevator patch = new Elevator();
        patch.id = el.id;
        patch.projectName = v;
        elevatorMapper.updateById(patch);
    }

    /** 越权 1403。 */
    public void updateLocal(String id, String empId, Map<String, Object> body) {
        Elevator el = get(id);
        if (el == null) {
            throw new BizException(1404, "电梯不存在");
        }
        Employee e = scopeService.require(empId);
        Set<String> ids = scopeService.visibleWorkerPlatformIds(e);
        if (ids != null && !assignedToOpenOrder(id, e.platformId)) {
            throw new BizException(1403, "仅可维护本人负责的电梯（需先在工单池认领该梯在途工单）");
        }

        LambdaUpdateWrapper<Elevator> uw = new LambdaUpdateWrapper<Elevator>().eq(Elevator::getId, id);
        boolean any = false;
        if (body != null && body.containsKey("projectName")) {
            String v = str(body, "projectName").trim();
            if (v.isEmpty()) {
                throw new BizException(422, "项目名称不能为空");
            }
            uw.set(Elevator::getProjectName, v).set(Elevator::getElevatorName, v);
            any = true;
        }
        if (body != null && body.containsKey("address")) {
            uw.set(Elevator::getLocation, str(body, "address"));
            any = true;
        }
        if (body != null && body.containsKey("brand")) {
            uw.set(Elevator::getBrand, str(body, "brand"));
            any = true;
        }
        if (body != null && body.containsKey("manufacturer")) {
            uw.set(Elevator::getManufacturer, str(body, "manufacturer"));
            any = true;
        }
        if (body != null && body.containsKey("factoryNumber")) {
            uw.set(Elevator::getFactoryNumber, str(body, "factoryNumber"));
            any = true;
        }
        if (body != null && body.containsKey("driveMode")) {
            uw.set(Elevator::getDriveMode, str(body, "driveMode"));
            any = true;
        }
        if (body != null && body.containsKey("ratedLoad")) {
            uw.set(Elevator::getRatedLoad, intOrNull(str(body, "ratedLoad")));
            any = true;
        }
        if (body != null && body.containsKey("ratedLoadUnit")) {
            uw.set(Elevator::getRatedLoadUnit, str(body, "ratedLoadUnit"));
            any = true;
        }
        if (body != null && body.containsKey("ratedSpeed")) {
            String s = str(body, "ratedSpeed");
            uw.set(Elevator::getRatedSpeed, s.isBlank() ? null : decimalOrNull(s));
            any = true;
        }
        if (body != null && body.containsKey("ratedSpeedUnit")) {
            uw.set(Elevator::getRatedSpeedUnit, str(body, "ratedSpeedUnit"));
            any = true;
        }
        if (body != null && body.containsKey("stationsDoors")) {
            uw.set(Elevator::getStationsDoors, str(body, "stationsDoors"));
            any = true;
        }
        if (body != null && body.containsKey("nextCheckDate")) {
            uw.set(Elevator::getNextCheckDate, TimeUtil.parseDate(str(body, "nextCheckDate")));
            any = true;
        }
        if (body != null && body.containsKey("nextMaintenanceDate")) {
            uw.set(Elevator::getNextMaintenanceDate, TimeUtil.parseDate(str(body, "nextMaintenanceDate")));
            any = true;
        }
        if (!any) {
            throw new BizException(422, "无可更新的设备信息");
        }
        elevatorMapper.update(null, uw);
    }

    private static Integer intOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new BizException(422, "额定载重必须为整数");
        }
    }

    private static BigDecimal decimalOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new BizException(422, "额定速度必须为数字");
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
