package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.Employee;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.service.EmployeeScopeService;
import com.cqwlw.maintenance.service.UnitRecordService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/** 越权 403/1403 由 GlobalExceptionHandler 统一落 BLOCKED 审计（spec §5.2）。 */
@RestController
public class RecordController {

    private final UnitRecordService unitRecordService;
    private final EmployeeScopeService scopeService;

    public RecordController(UnitRecordService unitRecordService, EmployeeScopeService scopeService) {
        this.unitRecordService = unitRecordService;
        this.scopeService = scopeService;
    }

    @GetMapping("/records/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String id, HttpServletRequest req) {
        MultiRoleGate.requireRead(req, PermissionMatrix.RECORD_VIEW);
        Object empId = req.getAttribute(AuthInterceptor.ATTR_EMP_ID);
        Employee e = scopeService.require(empId == null ? null : String.valueOf(empId));
        Set<String> ids = scopeService.visibleReadPlatformIds(e);
        MaintainRecord r = unitRecordService.findOr404(id);
        if (ids != null
                && (r.workerPlatformId == null || !ids.contains(r.workerPlatformId))
                && (r.assistantPlatformId == null || !ids.contains(r.assistantPlatformId))) {
            throw new BizException(1403, "仅可查看本人或本班组记录");
        }
        Map<String, Object> m = unitRecordService.toMap(r);
        // shareToken 是匿名签署令牌：仅派单主/配合人本人可读，同班组其他读者剔除（否则可代客户签/拒签）
        if (!isAssignee(e, r)) {
            m.remove("shareToken");
        }
        return ApiResponse.ok(m);
    }

    private static boolean isAssignee(Employee e, MaintainRecord r) {
        String pid = e.platformId;
        return pid != null && !pid.isBlank()
                && (pid.equals(r.workerPlatformId) || pid.equals(r.assistantPlatformId));
    }
}
