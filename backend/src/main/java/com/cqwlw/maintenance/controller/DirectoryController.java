package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.auth.AuthInterceptor;
import com.cqwlw.maintenance.service.DirectoryService;
import com.cqwlw.maintenance.service.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 救援/故障/演练/自检/消息/知识库（docs/04 A.3/A.6/A.7）。
 */
@RestController
public class DirectoryController {

    private final DirectoryService directoryService;
    private final IdempotencyService idempotencyService;

    public DirectoryController(DirectoryService directoryService, IdempotencyService idempotencyService) {
        this.directoryService = directoryService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/rescues")
    public ApiResponse<Object> createRescue(@RequestBody Map<String, Object> body,
                                            @RequestParam(value = "_idem", required = false) String idemParam,
                                            @org.springframework.web.bind.annotation.RequestHeader(
                                                    value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey != null ? idemKey : idemParam, "/rescues");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = directoryService.createRescue(body);
        guard.commit(result);
        return ApiResponse.ok(result);
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

    @PostMapping("/faults")
    public ApiResponse<Object> createFault(@RequestBody Map<String, Object> body,
                                           HttpServletRequest request,
                                           @org.springframework.web.bind.annotation.RequestHeader(
                                                   value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/faults");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = directoryService.createFault(body, empId(request));
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    @GetMapping("/faults")
    public ApiResponse<Map<String, Object>> listFaults(@RequestParam(required = false) String page,
                                                       @RequestParam(required = false) String size,
                                                       @RequestParam(required = false) String status,
                                                       HttpServletRequest request) {
        Map<String, String> query = Map.of(
                "page", page == null ? "" : page,
                "size", size == null ? "" : size,
                "status", status == null ? "" : status);
        return ApiResponse.ok(directoryService.listFaults(query, empId(request)));
    }

    @GetMapping("/faults/{id}")
    public ApiResponse<Map<String, Object>> getFault(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(directoryService.getFault(id, empId(request)));
    }

    @PostMapping("/faults/{id}/close")
    public ApiResponse<Map<String, Object>> closeFault(@PathVariable String id,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(directoryService.closeFault(id, body));
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
                                                   value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/drills");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = directoryService.createDrill(body);
        guard.commit(result);
        return ApiResponse.ok(result);
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
                                                     value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/inspects");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = directoryService.createInspect(body);
        guard.commit(result);
        return ApiResponse.ok(result);
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
