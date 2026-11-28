package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.auth.MultiRoleGate;
import com.cqwlw.maintenance.auth.PermissionMatrix;
import com.cqwlw.maintenance.service.DirectoryService;
import com.cqwlw.maintenance.service.RecordPdfService;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.service.WorkDomainService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class DirectoryController {

    private final DirectoryService directoryService;
    private final IdempotencyService idempotencyService;
    private final RecordPdfService pdfService;
    private final WorkDomainService workDomainService;

    public DirectoryController(DirectoryService directoryService, IdempotencyService idempotencyService,
                               RecordPdfService pdfService, WorkDomainService workDomainService,
                               com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper) {
        this.directoryService = directoryService;
        this.idempotencyService = idempotencyService;
        this.pdfService = pdfService;
        this.workDomainService = workDomainService;
        this.userRoleMapper = userRoleMapper;
    }

    private final com.cqwlw.maintenance.mapper.SysUserRoleMapper userRoleMapper;

    private String unitAnchor(HttpServletRequest request) {
        com.cqwlw.maintenance.entity.SysUserRole row = userRoleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.cqwlw.maintenance.entity.SysUserRole>()
                        .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserId, empId(request))
                        .eq(com.cqwlw.maintenance.entity.SysUserRole::getUserType, "EMPLOYEE")
                        .likeRight(com.cqwlw.maintenance.entity.SysUserRole::getRoleCode, "CLIENT")
                        .last("LIMIT 1"));
        return row == null ? null : row.orgId;
    }

    @PostMapping("/faults/{id}/grab")
    public ApiResponse<Object> grabFault(@PathVariable String id,
                                         @org.springframework.web.bind.annotation.RequestHeader(
                                                 value = "X-Idempotency-Key", required = false) String idemKey,
                                         HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, Map.of(), request,
                () -> directoryService.grabFault(id, empId(request))));
    }

    @PostMapping("/faults/{id}/arrive")
    public ApiResponse<Object> arriveFault(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           @org.springframework.web.bind.annotation.RequestHeader(
                                                   value = "X-Idempotency-Key", required = false) String idemKey,
                                           HttpServletRequest request) {
        Double lat = body.get("lat") instanceof Number n ? n.doubleValue() : null;
        Double lng = body.get("lng") instanceof Number n2 ? n2.doubleValue() : null;
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> directoryService.arriveFault(id, empId(request), lat, lng)));
    }

    @PostMapping("/faults/{id}/submit")
    public ApiResponse<Object> submitFault(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           @org.springframework.web.bind.annotation.RequestHeader(
                                                   value = "X-Idempotency-Key", required = false) String idemKey,
                                           HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> directoryService.submitFault(id, empId(request), body)));
    }

    @PostMapping("/faults/{id}/confirm")
    public ApiResponse<Object> confirmFault(@PathVariable String id, @RequestBody Map<String, Object> body,
                                            @org.springframework.web.bind.annotation.RequestHeader(
                                                    value = "X-Idempotency-Key", required = false) String idemKey,
                                            HttpServletRequest request) {
        boolean pass = !Boolean.FALSE.equals(body.get("pass"));
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        String signature = body.get("signature") == null ? "" : String.valueOf(body.get("signature"));
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> signature.isBlank()
                        ? directoryService.confirmFault(id,
                                com.cqwlw.maintenance.auth.MultiRoleGate.roles(request), unitAnchor(request), pass, reason)
                        : directoryService.confirmFault(id,
                                com.cqwlw.maintenance.auth.MultiRoleGate.roles(request), unitAnchor(request), pass, reason,
                                signature)));
    }

    @PostMapping("/faults/{id}/cancel")
    public ApiResponse<Object> cancelFault(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           @org.springframework.web.bind.annotation.RequestHeader(
                                                   value = "X-Idempotency-Key", required = false) String idemKey,
                                           HttpServletRequest request) {
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> directoryService.cancelFault(id,
                        com.cqwlw.maintenance.auth.MultiRoleGate.roles(request), reason,
                        empId(request), unitAnchor(request))));
    }

    /** 服务评价（仅已闭环、1-5 星、一次性） */
    @PostMapping("/faults/{id}/evaluate")
    public ApiResponse<Object> evaluate(@PathVariable String id, @RequestBody Map<String, Object> body,
                                        @org.springframework.web.bind.annotation.RequestHeader(
                                                value = "X-Idempotency-Key", required = false) String idemKey,
                                        HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> directoryService.evaluate(id,
                        com.cqwlw.maintenance.auth.MultiRoleGate.roles(request), unitAnchor(request), body)));
    }

    @PostMapping("/rescues")
    public ApiResponse<Object> createRescue(@RequestBody Map<String, Object> body,
                                            @RequestParam(value = "_idem", required = false) String idemParam,
                                            @org.springframework.web.bind.annotation.RequestHeader(
                                                    value = "X-Idempotency-Key", required = false) String idemKey,
                                            HttpServletRequest request) {
        String key = idemKey != null ? idemKey : idemParam;
        return ApiResponse.ok(idempotencyService.executeDb(key, body, request,
                () -> directoryService.createRescue(body)));
    }

    @GetMapping("/rescues")
    public ApiResponse<Map<String, Object>> listRescues(@RequestParam(required = false) String page,
                                                        @RequestParam(required = false) String size) {
        Map<String, String> query = Map.of(
                "page", page == null ? "" : page,
                "size", size == null ? "" : size);
        List<Map<String, Object>> all = directoryService.listRescues();
        int p = Math.max(1, page(query.get("page")));
        int s = Math.max(1, size(query.get("size")));
        int from = Math.min((p - 1) * s, all.size());
        int to = Math.min(from + s, all.size());
        return ApiResponse.ok(Map.of("list", all.subList(from, to), "total", all.size()));
    }

    @GetMapping("/rescues/{id}")
    public ApiResponse<Map<String, Object>> getRescue(@PathVariable String id) {
        return ApiResponse.ok(directoryService.getRescue(id));
    }

    /** 管理端代报修：一次请求原子完成建单 + 来源登记 + 审计。 */
    @PostMapping("/faults/proxy")
    public ApiResponse<Object> createProxyFault(@RequestBody Map<String, Object> body,
                                                HttpServletRequest request,
                                                @org.springframework.web.bind.annotation.RequestHeader(
                                                        value = "X-Idempotency-Key", required = false) String idemKey) {
        MultiRoleGate.requireWrite(request, PermissionMatrix.ORDER_CREATE_PROXY);
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> workDomainService.createProxyFault(body, empId(request))));
    }

    @PostMapping("/faults")
    public ApiResponse<Object> createFault(@RequestBody Map<String, Object> body,
                                           HttpServletRequest request,
                                           @org.springframework.web.bind.annotation.RequestHeader(
                                                   value = "X-Idempotency-Key", required = false) String idemKey) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> directoryService.createFault(body, empId(request))));
    }

    @GetMapping("/faults")
    public ApiResponse<Map<String, Object>> listFaults(@RequestParam(required = false) String page,
                                                       @RequestParam(required = false) String size,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String trapped,
                                                       HttpServletRequest request) {
        Map<String, String> query = Map.of(
                "page", page == null ? "" : page,
                "size", size == null ? "" : size,
                "status", status == null ? "" : status,
                "trapped", trapped == null ? "" : trapped);
        return ApiResponse.ok(directoryService.listFaults(query, empId(request)));
    }

    @GetMapping("/faults/{id}")
    public ApiResponse<Map<String, Object>> getFault(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(directoryService.getFault(id, empId(request)));
    }

    /** 维修过程字段更新（小程序急修单详情页） */
    @PutMapping("/faults/{id}")
    public ApiResponse<Map<String, Object>> updateFault(@PathVariable String id,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(directoryService.updateFault(id, body, empId(request)));
    }
    @PostMapping("/faults/{id}/close")
    public ApiResponse<Map<String, Object>> closeFault(@PathVariable String id,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(directoryService.closeFault(id, body, empId(request)));
    }

    @GetMapping("/admin/faults/{id}/export-pdf")
    public ResponseEntity<byte[]> exportFaultPdf(@PathVariable String id, HttpServletRequest request) {
        Map<String, Object> view = directoryService.getFault(id, empId(request));
        byte[] pdf = pdfService.renderFault(view);
        String filename = URLEncoder.encode("急修单-" + id + ".pdf", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/admin/faults/{id}/dispatch")
    public ApiResponse<Map<String, Object>> dispatchFault(@PathVariable String id,
                                                          @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(directoryService.dispatchFault(id, String.valueOf(body.get("workerId"))));
    }

    private String empId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(AuthInterceptor.ATTR_EMP_ID));
    }

    @GetMapping("/drills")
    public ApiResponse<Map<String, Object>> listDrills() {
        return ApiResponse.ok(directoryService.listDrills());
    }

    @PostMapping("/drills")
    public ApiResponse<Object> createDrill(@RequestBody Map<String, Object> body,
                                           @org.springframework.web.bind.annotation.RequestHeader(
                                                   value = "X-Idempotency-Key", required = false) String idemKey,
                                           HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> directoryService.createDrill(body)));
    }

    @GetMapping("/inspects")
    public ApiResponse<List<Map<String, Object>>> listInspects() {
        return ApiResponse.ok(directoryService.listInspects());
    }

    @GetMapping("/inspects/template")
    public ApiResponse<Map<String, Object>> inspectTemplate(@RequestParam(required = false) String elevatorId) {
        return ApiResponse.ok(directoryService.inspectTemplate(elevatorId));
    }

    @PostMapping("/inspects")
    public ApiResponse<Object> createInspect(@RequestBody Map<String, Object> body,
                                             @org.springframework.web.bind.annotation.RequestHeader(
                                                     value = "X-Idempotency-Key", required = false) String idemKey,
                                             HttpServletRequest request) {
        return ApiResponse.ok(idempotencyService.executeDb(idemKey, body, request,
                () -> directoryService.createInspect(body)));
    }

    @GetMapping("/messages")
    public ApiResponse<Map<String, Object>> listMessages(@RequestParam(required = false) String page,
                                                         @RequestParam(required = false) String size) {
        Map<String, String> query = Map.of(
                "page", page == null ? "" : page,
                "size", size == null ? "" : size);
        return ApiResponse.ok(directoryService.listMessages(query));
    }

    @GetMapping("/messages/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        return ApiResponse.ok(directoryService.unreadCount());
    }

    @PostMapping("/messages/{id}/read")
    public ApiResponse<Map<String, Object>> readMessage(@PathVariable String id) {
        return ApiResponse.ok(directoryService.readMessage(id));
    }

    @PostMapping("/messages/read-all")
    public ApiResponse<Map<String, Object>> readAll() {
        return ApiResponse.ok(directoryService.readAll());
    }

    @GetMapping("/knowledge")
    public ApiResponse<Map<String, Object>> listKnowledge(@RequestParam(required = false) String keyword) {
        Map<String, String> query = Map.of("keyword", keyword == null ? "" : keyword);
        return ApiResponse.ok(directoryService.listKnowledge(query));
    }

    private static int page(String s) {
        try {
            return s == null || s.isEmpty() ? 1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static int size(String s) {
        try {
            return s == null || s.isEmpty() ? 20 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 20;
        }
    }
}
