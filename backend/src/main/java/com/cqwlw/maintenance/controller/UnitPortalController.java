package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.service.UnitPortalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 使用单位门户（W3，Task 10）：身份来自门户令牌（AuthInterceptor 写入 ATTR_UNIT_ID），
 * 数据域一律收敛到该 useUnitId，不接受请求体指定单位。
 * 报修复用急修链路（POST /unit/faults），与员工端 /faults 分开是为了避免单位令牌调用员工端点。
 */
@RestController
public class UnitPortalController {

    private final UnitPortalService portalService;

    public UnitPortalController(UnitPortalService portalService) {
        this.portalService = portalService;
    }

    /** 本单位电梯 */
    @GetMapping("/unit/elevators")
    public ApiResponse<List<Map<String, Object>>> elevators(HttpServletRequest request) {
        return ApiResponse.ok(portalService.elevators(unitId(request)));
    }

    /** 本单位维保记录 */
    @GetMapping("/unit/records/list")
    public ApiResponse<List<Map<String, Object>>> records(HttpServletRequest request) {
        return ApiResponse.ok(portalService.records(unitId(request)));
    }

    /** 待确认签字的记录 */
    @GetMapping("/unit/pending-signs")
    public ApiResponse<List<Map<String, Object>>> pendingSigns(HttpServletRequest request) {
        return ApiResponse.ok(portalService.pendingSigns(unitId(request)));
    }

    /** 本单位急修单 */
    @GetMapping("/unit/faults")
    public ApiResponse<List<Map<String, Object>>> faults(HttpServletRequest request) {
        return ApiResponse.ok(portalService.faults(unitId(request)));
    }

    /** 使用单位报修（从本单位电梯选，派给该梯责任人） */
    @PostMapping("/unit/faults")
    public ApiResponse<Map<String, Object>> reportFault(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(portalService.reportFault(unitId(request), body));
    }

    /** 门户身份：非门户令牌（员工令牌）调用时明确 403，避免误用 */
    private static String unitId(HttpServletRequest request) {
        Object id = request.getAttribute(AuthInterceptor.ATTR_UNIT_ID);
        if (id == null) {
            throw new BizException(403, "请使用使用单位账号登录门户");
        }
        return String.valueOf(id);
    }
}
