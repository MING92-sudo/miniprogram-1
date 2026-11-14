package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.entity.Fault;
import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.service.WorkDomainService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** M4 作业域端点：记录修订（record:amend）+ 代报修（order:create_proxy）。 */
@RestController
public class WorkDomainController {

    private final WorkDomainService service;

    public WorkDomainController(WorkDomainService service) {
        this.service = service;
    }

    private static String actor(HttpServletRequest req) {
        return String.valueOf(req.getAttribute(AuthInterceptor.ATTR_EMP_ID));
    }

    @PostMapping("/records/{id}/amend")
    public ApiResponse<MaintainRecord> amend(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.RECORD_AMEND);
        return ApiResponse.ok(service.amend(id, String.valueOf(body.get("reason")), actor(req)));
    }

    @PostMapping("/faults/{id}/proxy")
    public ApiResponse<Fault> proxy(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        MultiRoleGate.requireWrite(req, PermissionMatrix.ORDER_CREATE_PROXY);
        return ApiResponse.ok(service.proxyRepair(id, body, actor(req)));
    }
}