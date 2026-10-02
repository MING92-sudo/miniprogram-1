package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.OpLog;
import com.cqwlw.maintenance.mapper.EmployeeMapper;
import com.cqwlw.maintenance.entity.Employee;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cqwlw.maintenance.mapper.OpLogMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

        int p = Math.max(1, intOf(page, 1));
        int s = Math.max(1, Math.min(200, intOf(size, 20)));
        // 分页下推到 SQL：op_log 须留痕三年，全量加载会随时间线性劣化
        Page<OpLog> pageResult = opLogMapper.selectPage(new Page<>(p, s), w);
        List<OpLog> rows = pageResult.getRecords();

        Map<String, String> names = namesOf(rows);

        List<Map<String, Object>> list = new ArrayList<>();
        for (OpLog l : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.id);
            m.put("operatorId", l.operatorId == null ? "" : l.operatorId);
            m.put("operatorName", l.operatorId == null ? "" : names.getOrDefault(l.operatorId, l.operatorId));
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
        out.put("total", pageResult.getTotal());
        out.put("page", p);
        out.put("size", s);
        return ApiResponse.ok(out);
    }

    /** 只反查当页出现过的操作人，而不是整张员工表 */
    private Map<String, String> namesOf(List<OpLog> rows) {
        Set<String> ids = rows.stream()
                .map(l -> l.operatorId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                        .in(Employee::getId, ids)).stream()
                .collect(Collectors.toMap(e -> e.id, e -> e.name == null ? "" : e.name, (a, b) -> a));
    }

    private static int intOf(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}