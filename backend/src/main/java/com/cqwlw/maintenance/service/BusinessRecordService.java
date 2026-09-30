package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BusinessException;
import com.cqwlw.maintenance.entity.*;
import com.cqwlw.maintenance.mapper.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** MVP 台账与看板数据；后续按 docs/02 拆分为独立业务模块。 */
@Service
public class BusinessRecordService {
    private final BusinessRecordMapper businessRecordMapper;
    private final MessageRecordMapper messageRecordMapper;
    private final ElevatorMapper elevatorMapper;
    private final WorkOrderMapper workOrderMapper;
    private final ObjectMapper objectMapper;

    public BusinessRecordService(BusinessRecordMapper businessRecordMapper, MessageRecordMapper messageRecordMapper,
                                 ElevatorMapper elevatorMapper, WorkOrderMapper workOrderMapper,
                                 ObjectMapper objectMapper) {
        this.businessRecordMapper = businessRecordMapper;
        this.messageRecordMapper = messageRecordMapper;
        this.elevatorMapper = elevatorMapper;
        this.workOrderMapper = workOrderMapper;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> list(String type, Map<String, Object> query) {
        List<BusinessRecord> records = businessRecordMapper.selectList(new LambdaQueryWrapper<BusinessRecord>()
                .eq(BusinessRecord::getType, type)
                .orderByDesc(BusinessRecord::getCreatedAt));
        String status = text(query.get("status"));
        String keyword = text(query.get("keyword"));
        if (!status.isBlank()) {
            records = records.stream().filter(r -> status.equals(r.getStatus())).collect(Collectors.toList());
        }
        if (!keyword.isBlank()) {
            records = records.stream().filter(r -> content(r).toString().toLowerCase().contains(keyword.toLowerCase()))
                    .collect(Collectors.toList());
        }
        int page = Math.max(1, integer(query.get("page"), 1));
        int size = Math.max(1, integer(query.get("size"), 20));
        int from = Math.min((page - 1) * size, records.size());
        int to = Math.min(from + size, records.size());
        List<Map<String, Object>> list = records.subList(from, to).stream().map(this::toMap).collect(Collectors.toList());
        return Map.of("list", list, "total", records.size());
    }

    public Map<String, Object> detail(String type, Long id) {
        BusinessRecord record = required(type, id);
        return toMap(record);
    }

    public Map<String, Object> create(String type, Map<String, Object> body, String createdBy) {
        BusinessRecord record = new BusinessRecord();
        record.setType(type);
        record.setStatus(text(body.getOrDefault("status", defaultStatus(type))));
        body.remove("id");
        body.putIfAbsent("createdAt", LocalDateTime.now().toString());
        record.setContentJson(objectMapper.valueToTree(body).toString());
        record.setCreatedBy(createdBy);
        record.setCreatedAt(LocalDateTime.now());
        businessRecordMapper.insert(record);
        return toMap(record);
    }

    public Map<String, Object> update(String type, Long id, Map<String, Object> body) {
        BusinessRecord record = required(type, id);
        Map<String, Object> content = content(record);
        body.remove("id");
        content.putAll(body);
        record.setStatus(text(body.getOrDefault("status", record.getStatus())));
        record.setContentJson(objectMapper.valueToTree(content).toString());
        record.setUpdatedAt(LocalDateTime.now());
        businessRecordMapper.updateById(record);
        return toMap(record);
    }

    public Map<String, Object> summary() {
        LocalDate today = LocalDate.now();
        LocalDate soonEnd = today.plusDays(3);
        List<WorkOrder> orders = workOrderMapper.selectList(null);
        int dueToday = 0, dueSoon = 0, overdue = 0, inProgress = 0;
        for (WorkOrder order : orders) {
            LocalDate day = order.getPlanTime() == null ? null : order.getPlanTime().toLocalDate();
            if ("PROCESSING".equals(order.getStatus())) inProgress++;
            if (day == null || "DONE".equals(order.getStatus())) continue;
            if (day.isEqual(today)) dueToday++;
            else if (day.isAfter(today) && !day.isAfter(soonEnd)) dueSoon++;
            else if (day.isBefore(today)) overdue++;
        }
        long unconfirmed = businessRecordMapper.selectCount(new LambdaQueryWrapper<BusinessRecord>()
                .eq(BusinessRecord::getType, "UNIT_CONFIRM")
                .eq(BusinessRecord::getStatus, "PENDING"));
        long openFaults = businessRecordMapper.selectCount(new LambdaQueryWrapper<BusinessRecord>()
                .eq(BusinessRecord::getType, "FAULT")
                .eq(BusinessRecord::getStatus, "OPEN"));
        long overdueInspects = businessRecordMapper.selectCount(new LambdaQueryWrapper<BusinessRecord>()
                .eq(BusinessRecord::getType, "INSPECT")
                .eq(BusinessRecord::getStatus, "逾期未检"));
        Map<String, Object> summary = new HashMap<>();
        summary.put("dueToday", dueToday);
        summary.put("dueSoon", dueSoon);
        summary.put("overdue", overdue);
        summary.put("inProgress", inProgress);
        summary.put("unconfirmed", unconfirmed);
        summary.put("platformTotal", elevatorMapper.selectCount(null));
        summary.put("openFaults", openFaults);
        summary.put("overdueInspects", overdueInspects);
        summary.put("warnCount", dueToday + overdue);
        return summary;
    }

    public Map<String, Object> messages(Map<String, Object> query) {
        List<MessageRecord> records = messageRecordMapper.selectList(new LambdaQueryWrapper<MessageRecord>()
                .orderByDesc(MessageRecord::getCreatedAt));
        int page = Math.max(1, integer(query.get("page"), 1));
        int size = Math.max(1, integer(query.get("size"), 20));
        int from = Math.min((page - 1) * size, records.size());
        int to = Math.min(from + size, records.size());
        return Map.of("list", records.subList(from, to), "total", records.size());
    }

    public long unreadCount() {
        return messageRecordMapper.selectCount(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getRead, false));
    }

    public void markRead(Long id) {
        MessageRecord record = messageRecordMapper.selectById(id);
        if (record != null) {
            record.setRead(true);
            messageRecordMapper.updateById(record);
        }
    }

    public void markAllRead() {
        List<MessageRecord> records = messageRecordMapper.selectList(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getRead, false));
        for (MessageRecord record : records) {
            record.setRead(true);
            messageRecordMapper.updateById(record);
        }
    }

    private BusinessRecord required(String type, Long id) {
        BusinessRecord record = businessRecordMapper.selectById(id);
        if (record == null || !type.equals(record.getType())) {
            throw new BusinessException(1404, "记录不存在");
        }
        return record;
    }

    private Map<String, Object> toMap(BusinessRecord record) {
        Map<String, Object> view = new HashMap<>(content(record));
        view.put("id", String.valueOf(record.getId()));
        view.put("type", record.getType());
        view.put("status", record.getStatus());
        view.put("createdAt", record.getCreatedAt());
        view.put("updatedAt", record.getUpdatedAt());
        return view;
    }

    private Map<String, Object> content(BusinessRecord record) {
        try {
            return objectMapper.readValue(record.getContentJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String defaultStatus(String type) {
        return switch (type) {
            case "FAULT" -> "OPEN";
            case "INSPECT", "UNIT_CONFIRM" -> "PENDING";
            default -> "已完成";
        };
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
}
