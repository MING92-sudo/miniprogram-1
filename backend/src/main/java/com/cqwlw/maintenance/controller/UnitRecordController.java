package com.cqwlw.maintenance.controller;

import com.cqwlw.maintenance.common.ApiResponse;
import com.cqwlw.maintenance.service.IdempotencyService;
import com.cqwlw.maintenance.service.UnitRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 使用单位确认：sign-view / confirm-by-token 凭分享 token 免登录访问。
 */
@RestController
public class UnitRecordController {

    private final UnitRecordService unitRecordService;
    private final IdempotencyService idempotencyService;

    public UnitRecordController(UnitRecordService unitRecordService, IdempotencyService idempotencyService) {
        this.unitRecordService = unitRecordService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/unit/records/pending")
    public ApiResponse<Map<String, Object>> pending(@RequestParam(required = false) String page,
                                                    @RequestParam(required = false) String size) {
        Map<String, String> query = Map.of(
                "page", page == null ? "" : page,
                "size", size == null ? "" : size);
        return ApiResponse.ok(unitRecordService.pending(query));
    }

    @GetMapping("/unit/records/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
        return ApiResponse.ok(unitRecordService.get(id));
    }

    @PostMapping("/unit/records/{id}/confirm")
    public ApiResponse<Object> confirm(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                       @org.springframework.web.bind.annotation.RequestHeader(
                                               value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/unit/records/confirm");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = unitRecordService.confirm(id, body == null ? Map.of() : body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }

    @GetMapping("/unit/records/{id}/sign-view")
    public ApiResponse<Map<String, Object>> signView(@PathVariable String id,
                                                     @RequestParam(required = false) String token) {
        return ApiResponse.ok(unitRecordService.signView(id, token));
    }

    @PostMapping("/unit/records/{id}/confirm-by-token")
    public ApiResponse<Object> confirmByToken(@PathVariable String id,
                                              @RequestParam(required = false) String token,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              @org.springframework.web.bind.annotation.RequestHeader(
                                                      value = "X-Idempotency-Key", required = false) String idemKey) {
        IdempotencyService.Guard guard = idempotencyService.begin(idemKey, "/unit/records/confirm-by-token");
        if (guard.replayed()) {
            return ApiResponse.ok(guard.replayedResult());
        }
        Map<String, Object> result = unitRecordService.confirmByToken(id, token, body == null ? Map.of() : body);
        guard.commit(result);
        return ApiResponse.ok(result);
    }
}
