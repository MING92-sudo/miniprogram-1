package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.AdminScheduleService;
import com.cqwlw.maintenance.service.WorkOrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端·计划调度与延期审批（docs/04 A.5 落地，docs/09 二期）。
 * 角色：读 LEADER+；指派/生成/审批 = ADMIN/SYS_ADMIN；发起延期/转派 = 班组长及以上（LEADER+，
 * 拦截器 LEADER 可写例外）。校验失败码：1004（platform_id 未同步）/1007（互斥/证件/重复派单）。
 */
@RestController
public class AdminScheduleController {

    private final AdminScheduleService scheduleService;
    private final com.cqwlw.maintenance.service.DispatchService dispatchService;
    private final WorkOrderService workOrderService;

    public AdminScheduleController(AdminScheduleService scheduleService,
                                 com.cqwlw.maintenance.service.DispatchService dispatchService,
                                 WorkOrderService workOrderService) {
        this.scheduleService = scheduleService;
        this.dispatchService = dispatchService;
        this.workOrderService = workOrderService;
    }

    /**
     * 到期投影（2026-11-21 管理端维保计划页改版）：与自动派单同一基线的只读投影，
     * 解决 maintain_plan 表无数据（D-A1 不做批量生成）导致计划页恒空的问题。不落库、不触发派单。
     * 区域抢单池（D2）：行内 regionStaffed 为区域覆盖提示（false = 该区域无持平台ID的在职维保员，
     * 前端标「区域无人」预警），取代原绑定标记 bound。
     */
    @GetMapping("/admin/plans/projection")
    public ApiResponse<Object> projection() {
        return ApiResponse.ok(dispatchService.dueProjection());
    }

    @GetMapping("/admin/plans")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String elevatorId,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return ApiResponse.ok(scheduleService.listPlans(Map.of(
                "dateFrom", nz(dateFrom), "dateTo", nz(dateTo), "status", nz(status),
                "elevatorId", nz(elevatorId), "assignee", nz(assignee),
                "page", nz(page), "size", nz(size))));
    }

    @PostMapping("/admin/plans/generate")
    public ApiResponse<Map<String, Object>> generate(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return ApiResponse.ok(scheduleService.generatePlans(
                str(b, "dateFrom"), str(b, "dateTo"), str(b, "useUnitId")));
    }

    @GetMapping("/admin/plans/schedule-suggestion")
    public ApiResponse<Map<String, Object>> suggestion(@RequestParam String planId) {
        return ApiResponse.ok(scheduleService.suggestion(planId));
    }

    @GetMapping("/admin/plans/conflicts")
    public ApiResponse<Map<String, Object>> conflicts(@RequestParam String planId) {
        return ApiResponse.ok(scheduleService.conflicts(planId));
    }

    @GetMapping("/admin/plans/delays")
    public ApiResponse<Map<String, Object>> delays(@RequestParam(required = false) String status) {
        return ApiResponse.ok(scheduleService.listDelays(nz(status)));
    }

    @GetMapping("/admin/plans/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResponse.ok(scheduleService.planDetail(id));
    }

    @PutMapping("/admin/plans/{id}/assign")
    public ApiResponse<Map<String, Object>> assign(@PathVariable String id,
                                                   @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(scheduleService.assign(id,
                str(body, "principalId"), str(body, "assistantId"), str(body, "planDate")));
    }

    @PostMapping("/admin/plans/{id}/delay")
    public ApiResponse<Map<String, Object>> delay(@PathVariable String id,
                                                  @RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        return ApiResponse.ok(scheduleService.applyDelay(id, str(body, "reason"),
                intOrNull(body.get("delayDays")), str(body, "expectedDate"),
                String.valueOf(request.getAttribute(AuthInterceptor.ATTR_EMP_ID))));
    }

    @PutMapping("/admin/plans/delays/{id}/decide")
    public ApiResponse<Map<String, Object>> decide(@PathVariable String id,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        boolean approved = Boolean.parseBoolean(String.valueOf(body.get("approved")));
        return ApiResponse.ok(scheduleService.decideDelay(id, approved,
                str(body, "comment"), String.valueOf(request.getAttribute(AuthInterceptor.ATTR_EMP_ID))));
    }

    @PostMapping("/admin/orders/{id}/transfer")
    public ApiResponse<Map<String, Object>> transfer(@PathVariable String id,
                                                     @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(scheduleService.transfer(id, str(body, "toEmployeeId"), str(body, "reason")));
    }

    /**
     * 退回池（2026-11-20 区域抢单池设计 §4.4 / D10/M12，order:dispatch 权限）：
     * 被错领/认领人无法继续的工单清回区域池——审计先行（操作人/原认领人/原配合人/签到留痕快照），
     * 同事务清空认领与签到留痕回 PENDING；DONE 422；未认领 PENDING 幂等 ok。
     */
    @PostMapping("/admin/orders/{id}/release-to-pool")
    public ApiResponse<Map<String, Object>> releaseToPool(@PathVariable String id,
                                                          HttpServletRequest request) {
        MultiRoleGate.requireWrite(request, PermissionMatrix.ORDER_DISPATCH);
        return ApiResponse.ok(workOrderService.releaseToPool(id,
                String.valueOf(request.getAttribute(AuthInterceptor.ATTR_EMP_ID))));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Integer intOrNull(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
