package com.cqwlw.maintenance.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RoleCatalog;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.MaintenanceContract;
import com.cqwlw.maintenance.mapper.MaintenanceContractMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 维保主管仅期与台量（金额掩码）； */
@RestController
public class ContractLedgerController {

    private static final Set<String> FULL_AMOUNT = Set.of("MAINT_MGR", "PLATFORM_ADMIN", "SYS_ADMIN");

    private final MaintenanceContractMapper contractMapper;

    public ContractLedgerController(MaintenanceContractMapper contractMapper) {
        this.contractMapper = contractMapper;
    }

    @GetMapping("/contracts-ledger")
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.CONTRACT_VIEW);
        boolean full = false;
        for (String raw : MultiRoleGate.roles(req)) {
            String role = RoleCatalog.canonical(raw);
            if (role != null && FULL_AMOUNT.contains(role)) {
                full = true;
                break;
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MaintenanceContract c : contractMapper.selectList(new LambdaQueryWrapper<MaintenanceContract>().orderByDesc(MaintenanceContract::getEndDate))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id);
            row.put("contractNo", c.contractNo);
            row.put("useUnitId", c.useUnitId);
            row.put("startDate", String.valueOf(c.startDate));
            row.put("endDate", String.valueOf(c.endDate));
            row.put("status", c.status);
            if (full) {
                row.put("amount", c.amount);
                row.put("amountMasked", false);
            } else {
                row.put("amount", null);
                row.put("amountMasked", true);
                row.put("amountText", "¥ ***万（仅期与台量）");
            }
            out.add(row);
        }
        return ApiResponse.ok(out);
    }
}