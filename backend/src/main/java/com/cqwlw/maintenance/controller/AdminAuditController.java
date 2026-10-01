package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.OpLog;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.entity.Employee;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.mapper.OpLogMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 管理端·操作审计日志（op_log 全量留痕，SYS_ADMIN 只读） */
@RestController
public class AdminAuditController {

    private final OpLogMapper opLogMapper;
    private final EmployeeMapper employeeMapper;

    public AdminAuditController(OpLogMapper opLogMapper, EmployeeMapper employeeMapper) {
        this.opLogMapper = opLogMapper;
        this.employeeMapper = employeeMapper;
    }

    @GetMapping("/admin/op-logs")
    public ApiResponse<Map<String, Object>> logs(
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        LambdaQueryWrapper<OpLog> w = new LambdaQueryWrapper<>();
        if (operatorId != null && !operatorId.isBlank()) {
            w.eq(OpLog::getOperatorId, operatorId);
        }
        if (path != null && !path.isBlank()) {
            w.like(OpLog::getPath, path);
        }
        w.orderByDesc(OpLog::getCreatedAt);
        List<OpLog> all = opLogMapper.selectList(w);

        int p = intOf(page, 1);
        int s = intOf(size, 20);
        int from = Math.min((p - 1) * s, all.size());
        int to = Math.min(from + s, all.size());

        Map<String, String> names = employeeMapper.selectList(new LambdaQueryWrapper<Employee>()).stream()
                .collect(Collectors.toMap(e -> e.id, e -> e.name == null ? "" : e.name));

        List<Map<String, Object>> list = new ArrayList<>();
        for (OpLog l : all.subList(from, to)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.id);
            m.put("operatorId", l.operatorId == null ? "" : l.operatorId);
            m.put("operatorName", names.getOrDefault(l.operatorId, l.operatorId));
            m.put("method", l.method);
            m.put("path", l.path);
            m.put("action", l.action == null ? "" : l.action);
            m.put("result", l.result);
            m.put("ip", l.ip == null ? "" : l.ip);
            m.put("createdAt", TimeUtil.format(l.createdAt));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", list);
        out.put("total", all.size());
        out.put("page", p);
        out.put("size", s);
        return ApiResponse.ok(out);
    }

    private static int intOf(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
