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
    private final com.cqwlw.maintenance.mapper.ContractElevatorMapper contractElevatorMapper;

    public ContractLedgerController(MaintenanceContractMapper contractMapper,
                                    com.cqwlw.maintenance.mapper.ContractElevatorMapper contractElevatorMapper) {
        this.contractMapper = contractMapper;
        this.contractElevatorMapper = contractElevatorMapper;
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
        List<MaintenanceContract> contracts = contractMapper.selectList(
                new LambdaQueryWrapper<MaintenanceContract>().orderByDesc(MaintenanceContract::getEndDate));
        // 台量按 contract_elevator 关联一次性聚合，避免逐行 count
        Map<String, Long> unitsByContract = new java.util.HashMap<>();
        for (com.cqwlw.maintenance.entity.ContractElevator ce : contractElevatorMapper.selectList(null)) {
            unitsByContract.merge(ce.contractId, 1L, Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (MaintenanceContract c : contracts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id);
            row.put("contractNo", c.contractNo);
            row.put("useUnitId", c.useUnitId);
            row.put("startDate", String.valueOf(c.startDate));
            row.put("endDate", String.valueOf(c.endDate));
            row.put("cycle", c.startDate + " ~ " + c.endDate);
            row.put("units", unitsByContract.getOrDefault(c.id, 0L));
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