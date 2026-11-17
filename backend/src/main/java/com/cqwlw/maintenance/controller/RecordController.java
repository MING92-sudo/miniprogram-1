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

/**
 * 记录域内部读端点（C-24，docs/12）：GET /records/{id} 供小程序 record-detail 员工链路。
 * 门禁链：MultiRoleGate.requireRead(record:view)（冻结矩阵 Y=MAINT_SUP/MAINT_MGR/PLATFORM_ADMIN/SYS_ADMIN，
 * C=MAINT_TECH；客户/安装部/商务 DENY→403）→ EmployeeScopeService.require（会话失效 401）→
 * V7 班组读谓词 visibleReadPlatformIds（与 WorkOrderService.scopeOrders 同款：null=管理角色不限；
 * 非 null 须命中主维保或配合人员 platformId，否则 1403 无副作用，空集=非现场岗位任何记录 1403）。
 * 越权 403/1403 由 GlobalExceptionHandler 统一落 BLOCKED 审计（spec §5.2）。
 * 投影走 toMap 内部全量视图（含生命周期/版本链/shareToken/context/items 明细）；
 * 客户路径零改动：/unit/records/{id} 仍走 getAs（requireClient + toClientMap 白名单，员工令牌 1403）。
 */
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
        // V7 班组读谓词：越权 1403 且不产生副作用（读路径无写操作；防全量记录泄漏）。
        // NULL 守卫（ElevatorService.profile 同款范式）：存量记录 worker/assistant_platform_id 可为 NULL，
        // NO_FIELD 空集为 Set.of() 不可变集，contains(null) 抛 NPE→500 且丢失 BLOCKED 审计；
        // NULL 一律视为不命中 → 1403（空集任何记录 1403 的不变量对 NULL 行同样成立）
        if (ids != null
                && (r.workerPlatformId == null || !ids.contains(r.workerPlatformId))
                && (r.assistantPlatformId == null || !ids.contains(r.assistantPlatformId))) {
            throw new BizException(1403, "仅可查看本人或本班组记录");
        }
        return ApiResponse.ok(unitRecordService.toMap(r));
    }
}
