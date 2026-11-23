package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.auth.RequirePerm;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.AdminArchiveService;
import com.cqwlw.maintenance.service.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
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

@RestController
public class AdminArchiveController {

    private final AdminArchiveService archiveService;
    private final IdempotencyService idempotencyService;

    public AdminArchiveController(AdminArchiveService archiveService, IdempotencyService idempotencyService) {
        this.archiveService = archiveService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/company")
    public ApiResponse<Map<String, Object>> company() {
        return ApiResponse.ok(archiveService.companyView());
    }

    @PutMapping("/company")
    public ApiResponse<Object> updateCompany(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                             HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> archiveService.updateCompany(body)));
    }

    @GetMapping("/use-units")
    public ApiResponse<List<Map<String, Object>>> useUnits() {
        return ApiResponse.ok(archiveService.useUnitList());
    }

    @PostMapping("/use-units")
    public ApiResponse<Object> createUseUnit(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                             HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> archiveService.createUseUnit(body)));
    }

    @PutMapping("/use-units/{id}")
    public ApiResponse<Object> updateUseUnit(@PathVariable String id,
                                             @RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                             HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> archiveService.updateUseUnit(id, body)));
    }

    @GetMapping("/employees")
    @RequirePerm(PermissionMatrix.USER_VIEW)
    public ApiResponse<List<Map<String, Object>>> employees() {
        return ApiResponse.ok(archiveService.employeeList());
    }

    @PostMapping("/employees")
    public ApiResponse<Object> createEmployee(@RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                              HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> archiveService.createEmployee(body)));
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
                                              @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                              HttpServletRequest request) {
        String actorId = String.valueOf(request.getAttribute(
                com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID));
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> archiveService.updateEmployee(id, body, actorId)));
    }

    @PutMapping("/admin/employees/{id}/enabled")
    public ApiResponse<Object> setEnabled(@PathVariable String id,
                                          @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        return ApiResponse.ok(archiveService.setEmployeeEnabled(id, enabled));
    }

    /** 重置密码 */
    @PostMapping("/employees/{id}/reset-password")
    public ApiResponse<Object> resetPasswordRandom(@PathVariable String id,
                                                   @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, Map.of(), request,
                () -> archiveService.resetEmployeePassword(id, "")));
    }

    @PostMapping("/elevators")
    public ApiResponse<Object> createElevator(@RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                              HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> archiveService.createElevator(body)));
    }

    @PutMapping("/elevators/{id}")
    public ApiResponse<Object> updateElevator(@PathVariable String id,
                                              @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                              HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> archiveService.updateElevator(id, body)));
    }

    @PostMapping("/elevators/batch-geo")
    public ApiResponse<Object> batchGeo(@RequestBody Map<String, Object> body,
                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                        HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> archiveService.batchGeo(body)));
    }

    @PostMapping("/elevators/{id}/qrcode")
    public org.springframework.http.ResponseEntity<byte[]> elevatorQrCode(@PathVariable String id) {
        byte[] png = archiveService.elevatorQrPng(id);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "image/png")
                .header("Content-Disposition", "attachment; filename=qrcode-" + id + ".png")
                .body(png);
    }

    @PostMapping("/company/sync-entity-id")
    public ApiResponse<Object> syncCompanyEntityId(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
            HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeExternal(idemKey, Map.of(), request,
                archiveService::syncCompanyEntityId));
    }

    @PostMapping("/use-units/{id}/sync-entity-id")
    public ApiResponse<Object> syncUseUnitEntityId(@PathVariable String id,
                                                   @RequestHeader(value = "X-Idempotency-Key", required = false) String idemKey,
                                                   HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeExternal(idemKey, Map.of(), request,
                () -> archiveService.syncUseUnitEntityId(id)));
    }
}
