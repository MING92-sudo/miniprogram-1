package com.cqwlw.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.Elevator;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.LocationAppeal;
import com.cqwlw.maintenance.entity.WorkOrder;
import com.cqwlw.maintenance.mapper.ElevatorMapper;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.mapper.LocationAppealMapper;
import com.cqwlw.maintenance.mapper.WorkOrderMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定位异常申述审核。审核通过后工单 checkin_extra 写入 locationAppealApproved=true，
 * 供签到流程"补签到"放行。
 */
@Service
public class ApprovalService {

    private final LocationAppealMapper appealMapper;
    private final WorkOrderMapper orderMapper;
    private final EmployeeMapper employeeMapper;
    private final ElevatorMapper elevatorMapper;

    public ApprovalService(LocationAppealMapper appealMapper, WorkOrderMapper orderMapper,
                           EmployeeMapper employeeMapper, ElevatorMapper elevatorMapper) {
        this.appealMapper = appealMapper;
        this.orderMapper = orderMapper;
        this.employeeMapper = employeeMapper;
        this.elevatorMapper = elevatorMapper;
    }

    public Map<String, Object> submit(String workOrderId, Map<String, Object> body, String employeeId) {
        WorkOrder o = orderMapper.selectById(workOrderId);
        if (o == null) {
            throw new BizException(1404, "工单不存在");
        }
        LocationAppeal a = new LocationAppeal();
        a.id = Ids.next("ap");
        a.workOrderId = workOrderId;
        a.employeeId = employeeId;
        a.reason = str(body, "reason");
        a.photoKey = str(body, "photoKey");
        a.distance = decimal(body.get("distance"));
        a.threshold = decimal(body.get("threshold"));
        a.status = "PENDING";
        a.createdAt = TimeUtil.now();
        appealMapper.insert(a);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appealId", a.id);
        out.put("status", "PENDING");
        return out;
    }

    /** 1001 申诉闭环：存在已通过申述即视为地理围栏放行依据（WorkOrderService.checkin 调用） */
    public boolean hasApproved(String workOrderId) {
        Long n = appealMapper.selectCount(new LambdaQueryWrapper<LocationAppeal>()
                .eq(LocationAppeal::getWorkOrderId, workOrderId)
                .eq(LocationAppeal::getStatus, "APPROVED"));
        return n != null && n > 0;
    }

    public Map<String, Object> listApprovals(Map<String, String> q) {
        // bizType 一期仅 LOCATION_APPEAL；预留扩展（延期/解锁/确认撤销）
        LambdaQueryWrapper<LocationAppeal> w = new LambdaQueryWrapper<>();
        if (notBlank(q.get("status"))) {
            w.eq(LocationAppeal::getStatus, q.get("status"));
        }
        w.orderByDesc(LocationAppeal::getCreatedAt);
        List<LocationAppeal> all = appealMapper.selectList(w);
        int page = intOf(q.get("page"), 1);
        int size = intOf(q.get("size"), 20);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (LocationAppeal a : all.subList(from, to)) {
            list.add(row(a));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", all.size());
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    public Map<String, Object> audit(String appealId, boolean approved, String comment, String reviewerId) {
        LocationAppeal a = appealMapper.selectById(appealId);
        if (a == null) {
            throw new BizException(1404, "申述不存在");
        }
        if (!"PENDING".equals(a.status)) {
            throw new BizException(422, "该申述已处理");
        }
        a.status = approved ? "APPROVED" : "REJECTED";
        a.reviewerId = reviewerId;
        a.reviewComment = comment == null ? "" : comment;
        a.reviewedAt = TimeUtil.now();
        appealMapper.updateById(a);

        if (approved) {
            // 补签到解锁：checkin_extra 合并 locationAppealApproved=true
            WorkOrder o = orderMapper.selectById(a.workOrderId);
            if (o != null) {
                Map<String, Object> extra = JsonUtil.readMap(o.checkinExtraJson);
                if (extra == null) {
                    extra = new LinkedHashMap<>();
                }
                extra.put("locationAppealApproved", true);
                extra.put("appealId", appealId);
                o.checkinExtraJson = JsonUtil.write(extra);
                orderMapper.updateById(o);
            }
        }
        return row(a);
    }

    private Map<String, Object> row(LocationAppeal a) {
        WorkOrder o = orderMapper.selectById(a.workOrderId);
        Elevator el = o == null ? null : elevatorMapper.selectById(o.elevatorId);
        Employee emp = a.employeeId == null ? null : employeeMapper.selectById(a.employeeId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("bizType", "LOCATION_APPEAL");
        m.put("workOrderId", a.workOrderId);
        m.put("orderNo", o == null ? "" : (o.orderNo == null ? "" : o.orderNo));
        m.put("elevatorName", el == null ? "" : (el.elevatorName == null ? "" : el.elevatorName));
        m.put("employeeName", emp == null ? "" : (emp.name == null ? "" : emp.name));
        m.put("reason", a.reason);
        m.put("photoKey", a.photoKey == null ? "" : a.photoKey);
        m.put("distance", a.distance == null ? "" : String.valueOf(a.distance));
        m.put("threshold", a.threshold == null ? "" : String.valueOf(a.threshold));
        m.put("status", a.status);
        m.put("reviewComment", a.reviewComment == null ? "" : a.reviewComment);
        m.put("createdAt", TimeUtil.format(a.createdAt));
        m.put("reviewedAt", TimeUtil.format(a.reviewedAt));
        return m;
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static int intOf(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
