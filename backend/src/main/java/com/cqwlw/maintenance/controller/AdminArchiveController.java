package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.IdempotencyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端·档案维护（docs/09 §4.1：维保单位/使用单位/人员/电梯）。
 * 写接口携带 X-Idempotency-Key（AGENTS §3）；手机号互斥预校验在服务层（1002+conflicts[]，docs/04 B.7）。
 * 写角色门禁（ADMIN/SYS_ADMIN）由 AdminRoleInterceptor 承担。
 */
@RestController
public class AdminArchiveController {

    private final AdminArchiveService archiveService;
    private final IdempotencyService idempotencyService;

    public AdminArchiveController(AdminArchiveService archiveService, IdempotencyService idempotencyService) {
        this.archiveService = archiveService;
        this.idempotencyService = idempotencyService;
    }

    // ── 维保单位 ──

    @GetMapping("/company")
    public ApiResponse<Map<String, Object>> company() {
        return ApiResponse.ok(archiveService.companyView());
    }

    @PutMapping("/company")
    public ApiResponse<Object> updateCompany(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/company");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = archiveService.updateCompany(body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    // ── 使用单位 ──

    @GetMapping("/use-units")
    public ApiResponse<List<Map<String, Object>>> useUnits() {
        return ApiResponse.ok(archiveService.useUnitList());
    }

    @PostMapping("/use-units")
    public ApiResponse<Object> createUseUnit(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/use-units");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = archiveService.createUseUnit(body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    @PutMapping("/use-units/{id}")
    public ApiResponse<Object> updateUseUnit(@PathVariable String id,
                                             @RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/use-units/update");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = archiveService.updateUseUnit(id, body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    // ── 人员 ──

    @GetMapping("/employees")
    public ApiResponse<List<Map<String, Object>>> employees() {
        return ApiResponse.ok(archiveService.employeeList());
    }

    @PostMapping("/employees")
    public ApiResponse<Object> createEmployee(@RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/employees");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = archiveService.createEmployee(body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    /** ①档案增删改查：删除（有在途工单/系统管理员/有关联电梯时 422 拒绝） */
    @DeleteMapping("/employees/{id}")
    public ApiResponse<Object> deleteEmployee(@PathVariable String id) {
        return ApiResponse.ok(archiveService.deleteEmployee(id));
    }

    @DeleteMapping("/use-units/{id}")
    public ApiResponse<Object> deleteUseUnit(@PathVariable String id) {
        return ApiResponse.ok(archiveService.deleteUseUnit(id));
    }

    @DeleteMapping("/elevators/{id}")
    public ApiResponse<Object> deleteElevator(@PathVariable String id) {
        return ApiResponse.ok(archiveService.deleteElevator(id));
    }
    @PutMapping("/employees/{id}")
    public ApiResponse<Object> updateEmployee(@PathVariable String id,
                                              @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/employees/update");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = archiveService.updateEmployee(id, body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    // ── 用户权限（SYS_ADMIN 专属，docs/04 A.1）──

    @PutMapping("/admin/employees/{id}/enabled")
    public ApiResponse<Object> setEnabled(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        return ApiResponse.ok(archiveService.setEmployeeEnabled(id, enabled));
    }

    @PutMapping("/admin/employees/{id}/password")
    public ApiResponse<Object> resetPassword(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(archiveService.resetEmployeePassword(id,
                body.get("password") == null ? "" : String.valueOf(body.get("password"))));
    }

    // ── 电梯 ──

    @PostMapping("/elevators")
    public ApiResponse<Object> createElevator(@RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/elevators");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = archiveService.createElevator(body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    @PutMapping("/elevators/{id}")
    public ApiResponse<Object> updateElevator(@PathVariable String id,
                                              @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/elevators/update");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = archiveService.updateElevator(id, body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }
}
