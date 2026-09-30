package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BusinessException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.config.PlatformProperties;
import com.cqwlw.maintenance.entity.*;
import com.cqwlw.maintenance.mapper.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cqwlw.maintenance.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** MVP 工单链路：列表、签到、清单填写、签退、记录生成与平台 2.6 上报。 */
@Service
public class WorkOrderService {
    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, Integer> INTERVAL_DAYS = Map.of(
            "FM", 30, "HM", 15, "TM", 90, "SM", 180, "OY", 365);

    private final WorkOrderMapper workOrderMapper;
    private final ElevatorMapper elevatorMapper;
    private final UseUnitMapper useUnitMapper;
    private final CompanyMapper companyMapper;
    private final EmployeeMapper employeeMapper;
    private final MaintainRecordMapper maintainRecordMapper;
    private final RegUploadLogMapper regUploadLogMapper;
    private final PlatformClient platformClient;
    private final AppProperties appProperties;
    private final PlatformProperties platformProperties;
    private final ObjectMapper objectMapper;
    private final AuthContext authContext;

    public WorkOrderService(WorkOrderMapper workOrderMapper, ElevatorMapper elevatorMapper,
                            UseUnitMapper useUnitMapper, CompanyMapper companyMapper,
                            EmployeeMapper employeeMapper, MaintainRecordMapper maintainRecordMapper,
                            RegUploadLogMapper regUploadLogMapper, PlatformClient platformClient,
                            AppProperties appProperties, PlatformProperties platformProperties,
                            ObjectMapper objectMapper, AuthContext authContext) {
        this.workOrderMapper = workOrderMapper;
        this.elevatorMapper = elevatorMapper;
        this.useUnitMapper = useUnitMapper;
        this.companyMapper = companyMapper;
        this.employeeMapper = employeeMapper;
        this.maintainRecordMapper = maintainRecordMapper;
        this.regUploadLogMapper = regUploadLogMapper;
        this.platformClient = platformClient;
        this.appProperties = appProperties;
        this.platformProperties = platformProperties;
        this.objectMapper = objectMapper;
        this.authContext = authContext;
    }

    public Map<String, Object> list(Map<String, Object> query) {
        List<WorkOrder> orders = workOrderMapper.selectList(null);
        String status = text(query.get("status"));
        String due = text(query.get("due"));
        String keyword = text(query.get("keyword"));
        LocalDate today = LocalDate.now();
        LocalDate soonEnd = today.plusDays(3);

        if (!status.isBlank()) {
            orders = orders.stream().filter(o -> status.equals(o.getStatus())).collect(Collectors.toList());
        }
        if (!due.isBlank()) {
            orders = orders.stream().filter(o -> {
                LocalDate day = o.getPlanTime() == null ? null : o.getPlanTime().toLocalDate();
                if (day == null || "DONE".equals(o.getStatus())) return false;
                return switch (due) {
                    case "today" -> day.isEqual(today);
                    case "soon" -> day.isAfter(today) && !day.isAfter(soonEnd);
                    case "overdue" -> day.isBefore(today);
                    default -> true;
                };
            }).collect(Collectors.toList());
        }
        if (!keyword.isBlank()) {
            Map<Long, Elevator> elevators = elevatorMap();
            orders = orders.stream().filter(o -> {
                Elevator elevator = elevators.get(o.getElevatorId());
                String haystack = (o.getOrderNo() + " " + (elevator == null ? "" :
                        elevator.getElevatorName() + " " + elevator.getElevatorCode() + " " +
                        elevator.getDeviceCode() + " " + elevator.getRegistrationCode() + " " +
                        safe(elevator.getInsideNumber()))).toLowerCase();
                return haystack.contains(keyword.toLowerCase());
            }).collect(Collectors.toList());
        }
        orders.sort(Comparator.comparing(WorkOrder::getPlanTime, Comparator.nullsLast(Comparator.reverseOrder())));

        int page = Math.max(1, integer(query.get("page"), 1));
        int size = Math.max(1, integer(query.get("size"), 20));
        int from = Math.min((page - 1) * size, orders.size());
        int to = Math.min(from + size, orders.size());
        List<Map<String, Object>> list = orders.subList(from, to).stream().map(this::toView).collect(Collectors.toList());
        return Map.of("list", list, "total", orders.size());
    }

    public Map<String, Object> detail(Long id) {
        WorkOrder order = required(id);
        return toView(order);
    }

    public Map<String, Object> resolveByElevator(String elevatorCode) {
        Elevator elevator = elevatorMapper.selectOne(new LambdaQueryWrapper<Elevator>()
                .eq(Elevator::getElevatorCode, elevatorCode));
        if (elevator == null) {
            throw new BusinessException(1404, "未识别的电梯二维码");
        }
        List<WorkOrder> orders = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getElevatorId, elevator.getId())
                .ne(WorkOrder::getStatus, "DONE")
                .orderByAsc(WorkOrder::getPlanTime));
        if (orders.isEmpty()) {
            throw new BusinessException(1404, "该电梯暂无进行中的工单");
        }
        return toView(orders.get(0));
    }

    public Map<String, Object> checkin(Long id, Map<String, Object> body) {
        WorkOrder order = required(id);
        if (!"PENDING".equals(order.getStatus())) {
            throw new BusinessException(1003, "当前状态不允许签到");
        }
        String role = text(body.get("role")).isBlank() ? "PRINCIPAL" : text(body.get("role"));
        if ("ASSISTANT".equals(role) && text(body.get("dynamicCode")).isBlank()) {
            throw new BusinessException(422, "配合人员签到必须携带双人动态码");
        }
        order.setStatus("PROCESSING");
        order.setCheckinTime(parseDateTime(text(body.get("collectedAt")), LocalDateTime.now()));
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);
        return Map.of("checkinId", String.valueOf(System.currentTimeMillis()), "passed", true, "geoStatus", "PROVIDED");
    }

    public Map<String, Object> verifyDynamicCode(Long id, Map<String, Object> body) {
        String code = text(body.get("code"));
        if (!code.matches("^\\d{6}$")) {
            throw new BusinessException(1003, "动态码错误");
        }
        return Map.of("ok", true);
    }

    public Map<String, Object> checklist(Long id) {
        WorkOrder order = required(id);
        return Map.of("checklistId", String.valueOf(order.getId()), "items", checklistItems(order));
    }

    public Map<String, Object> submitItem(Long orderId, String itemId, Map<String, Object> body) {
        WorkOrder order = required(orderId);
        List<Map<String, Object>> items = checklistItems(order);
        Map<String, Object> item = items.stream().filter(i -> itemId.equals(text(i.get("id"))))
                .findFirst().orElseThrow(() -> new BusinessException(1404, "检查项不存在"));
        List<String> copyFields = List.of("result", "value", "valueText", "abnormalDesc", "problemCode",
                "skipReason", "photoFileIds", "photos", "recordedAt");
        for (String field : copyFields) {
            if (body.containsKey(field)) item.put(field, body.get(field));
        }
        item.put("recordedAt", text(body.get("recordedAt")).isBlank()
                ? LocalDateTime.now().format(DATETIME) : text(body.get("recordedAt")));
        saveChecklist(order, items);
        return Map.of("ok", true);
    }

    public Map<String, Object> runItemThisTime(Long orderId, String itemId) {
        WorkOrder order = required(orderId);
        List<Map<String, Object>> items = checklistItems(order);
        Map<String, Object> item = items.stream().filter(i -> itemId.equals(text(i.get("id"))))
                .findFirst().orElseThrow(() -> new BusinessException(1404, "检查项不存在"));
        item.put("notInThisRun", false);
        item.put("runThisTime", true);
        saveChecklist(order, items);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> checkout(Long id, Map<String, Object> body) {
        WorkOrder order = required(id);
        if (!"PROCESSING".equals(order.getStatus())) {
            throw new BusinessException(1003, "请先完成签到");
        }
        List<Map<String, Object>> items = checklistItems(order);
        long unfinished = items.stream().filter(i -> !Boolean.TRUE.equals(i.get("notInThisRun"))
                && text(i.get("result")).isBlank()).count();
        if (unfinished > 0) {
            throw new BusinessException(1003, "还有 " + unfinished + " 项检查未填写");
        }
        LocalDateTime checkoutTime = parseDateTime(text(body.get("collectedAt")), LocalDateTime.now());
        long minutes = java.time.Duration.between(order.getCheckinTime(), checkoutTime).toMinutes();
        if (minutes < appProperties.getWorkDurationMinutes()) {
            throw new BusinessException(1003, "作业时长不足 " + appProperties.getWorkDurationMinutes() + " 分钟");
        }

        String originalRecordId = generateRecordId();
        order.setStatus("DONE");
        order.setCheckoutTime(checkoutTime);
        order.setDuration(formatDuration(java.time.Duration.between(order.getCheckinTime(), checkoutTime).toMillis()));
        order.setOriginalRecordId(originalRecordId);
        order.setReportStatus("SUBMITTED");
        order.setUpdatedAt(LocalDateTime.now());

        MaintainRecord record = new MaintainRecord();
        record.setWorkOrderId(order.getId());
        record.setOriginalRecordId(originalRecordId);
        record.setReportStatus("SUBMITTED");
        record.setStartTime(order.getCheckinTime());
        record.setEndTime(checkoutTime);
        record.setSnapshotJson(objectMapper.valueToTree(buildSnapshot(order)).toString());
        maintainRecordMapper.insert(record);

        String reportStatus = submitToPlatform(record, order);
        record.setReportStatus(reportStatus);
        maintainRecordMapper.updateById(record);
        order.setReportStatus(reportStatus);
        workOrderMapper.updateById(order);

        return Map.of(
                "workOrderId", String.valueOf(order.getId()),
                "duration", safe(order.getDuration()),
                "originalRecordId", originalRecordId,
                "reportStatus", reportStatus,
                "recordId", String.valueOf(record.getId()),
                "shareToken", UUID.randomUUID().toString().replace("-", "")
        );
    }

    private String submitToPlatform(MaintainRecord record, WorkOrder order) {
        long started = System.currentTimeMillis();
        Map<String, Object> snapshot;
        try {
            snapshot = objectMapper.readValue(record.getSnapshotJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BusinessException(500, "上报快照解析失败");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        snapshot.forEach((key, value) -> form.add(key, value == null ? "" : String.valueOf(value)));
        form.set("problemCode", snapshot.getOrDefault("problemCode", "[\"S0\"]").toString());

        RegUploadLog uploadLog = new RegUploadLog();
        uploadLog.setOriginalRecordId(record.getOriginalRecordId());
        uploadLog.setApi("2.6");
        uploadLog.setRequestBody(maskSensitive(objectMapper.valueToTree(form).toString()));
        uploadLog.setRetryCount(0);
        try {
            String url = propsApiUrl();
            ResponseEntity<String> response = platformClient.postForm(url, form);
            String body = response.getBody() == null ? "" : response.getBody();
            String code = objectMapper.readTree(body.isBlank() ? "{}" : body).path("code").asText();
            boolean success = response.getStatusCode().is2xxSuccessful() && "200".equals(code);
            uploadLog.setSuccess(success);
            uploadLog.setResponseBody(maskSensitive(body));
            uploadLog.setHttpStatus(response.getStatusCode().value());
            uploadLog.setPlatformCode(code);
            uploadLog.setCostMs(System.currentTimeMillis() - started);
            regUploadLogMapper.insert(uploadLog);
            return success ? "REPORTED" : "FAILED_MAX";
        } catch (BusinessException e) {
            uploadLog.setSuccess(false);
            uploadLog.setResponseBody(maskSensitive(e.getMessage()));
            uploadLog.setPlatformCode(String.valueOf(e.getCode()));
            uploadLog.setCostMs(System.currentTimeMillis() - started);
            regUploadLogMapper.insert(uploadLog);
            return "FAILED_MAX";
        } catch (Exception e) {
            log.warn("2.6 上报失败", e);
            uploadLog.setSuccess(false);
            uploadLog.setResponseBody(maskSensitive("上报异常"));
            uploadLog.setCostMs(System.currentTimeMillis() - started);
            regUploadLogMapper.insert(uploadLog);
            return "FAILED_MAX";
        }
    }

    private String propsApiUrl() {
        String baseUrl = platformProperties.getApiBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(2001, "监管平台地址未配置");
        }
        return baseUrl.endsWith("/") ? baseUrl + "elevator/maintenanceRecord" : baseUrl + "/elevator/maintenanceRecord";
    }

    private Map<String, Object> buildSnapshot(WorkOrder order) {
        Elevator elevator = elevatorMapper.selectById(order.getElevatorId());
        if (elevator == null) {
            throw new BusinessException(1404, "电梯不存在");
        }
        UseUnit unit = useUnitMapper.selectById(elevator.getUseUnitId());
        if (unit == null) {
            throw new BusinessException(1404, "使用单位不存在");
        }
        Company company = companyMapper.selectOne(new LambdaQueryWrapper<Company>().last("LIMIT 1"));
        if (company == null) {
            throw new BusinessException(1404, "维保单位档案不存在");
        }
        Employee worker = employeeMapper.selectOne(new LambdaQueryWrapper<Employee>()
                .eq(Employee::getPhone, authContext.currentPhone()));
        if (worker == null) {
            throw new BusinessException(1404, "维保人员不存在");
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("elevatorCode", elevator.getElevatorCode());
        snapshot.put("deviceCode", elevator.getDeviceCode());
        snapshot.put("unitPrincipal", unit.getUnitPrincipal());
        snapshot.put("unitPrincipalPhone", unit.getUnitPrincipalPhone());
        snapshot.put("elevatorAdminister", unit.getElevatorAdminister());
        snapshot.put("elevatorAdministerPhone", unit.getElevatorAdministerPhone());
        snapshot.put("emergencyPhone", unit.getEmergencyPhone());
        snapshot.put("insideNumber", safe(elevator.getInsideNumber()));
        snapshot.put("workMenegerName", company.getWorkManagerName());
        snapshot.put("workMenegerPhone", company.getWorkManagerPhone());
        snapshot.put("workMan1Id", worker.getPlatformId());
        snapshot.put("workMan2Id", safe(worker.getPlatformId()));
        snapshot.put("startTime", order.getCheckinTime().format(DATETIME));
        snapshot.put("endTime", order.getCheckoutTime().format(DATETIME));
        snapshot.put("workType", order.getWorkTypeCode());
        snapshot.put("recorder", worker.getName());
        snapshot.put("recorderPhone", worker.getPhone());
        snapshot.put("originalRecordId", order.getOriginalRecordId());
        snapshot.put("problemCode", "[\"S0\"]");
        snapshot.put("nextMaintenanceDate", order.getCheckoutTime().toLocalDate()
                .plusDays(INTERVAL_DAYS.getOrDefault(order.getWorkTypeCode(), 15)).toString());
        return snapshot;
    }

    private Map<String, Object> toView(WorkOrder order) {
        Map<String, Object> view = objectMapper.convertValue(order, new TypeReference<Map<String, Object>>() {});
        Elevator elevator = elevatorMapper.selectById(order.getElevatorId());
        if (elevator != null) {
            view.put("elevator", objectMapper.convertValue(elevator, Map.class));
            view.put("elevatorName", elevator.getElevatorName());
            view.put("elevatorCode", elevator.getElevatorCode());
            view.put("deviceCode", elevator.getDeviceCode());
            view.put("regCode", elevator.getRegistrationCode());
            view.put("insideNumber", elevator.getInsideNumber());
            view.put("model", elevator.getBrand());
            UseUnit unit = useUnitMapper.selectById(elevator.getUseUnitId());
            view.put("projectName", unit == null ? "" : unit.getUnitName());
        }
        view.put("checklist", checklistItems(order));
        return view;
    }

    private Map<Long, Elevator> elevatorMap() {
        return elevatorMapper.selectList(null).stream()
                .collect(Collectors.toMap(Elevator::getId, e -> e));
    }

    private List<Map<String, Object>> checklistItems(WorkOrder order) {
        try {
            return objectMapper.readValue(order.getChecklistJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveChecklist(WorkOrder order, List<Map<String, Object>> items) {
        order.setChecklistJson(objectMapper.valueToTree(items).toString());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.updateById(order);
    }

    private WorkOrder required(Long id) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(1404, "工单不存在");
        }
        return order;
    }

    private String generateRecordId() {
        return System.currentTimeMillis() + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
    }

    /** 上报日志只保留脱敏后的手机号片段，完整报文不在日志层明文留存。 */
    private String maskSensitive(String value) {
        return value == null ? "" : value.replaceAll("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)", "$1****$2");
    }

    private String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        return String.format("%02d:%02d:%02d", totalSeconds / 3600, totalSeconds % 3600 / 60, totalSeconds % 60);
    }

    private LocalDateTime parseDateTime(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDateTime.parse(value, DATETIME);
        } catch (Exception e) {
            return LocalDateTime.parse(value);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int integer(Object value, int fallback) {
        try {
            return Integer.parseInt(text(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
