package com.cqwlw.maintenance.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final com.cqwlw.maintenance.service.AuditLogService auditLogService;

    public GlobalExceptionHandler(com.cqwlw.maintenance.service.AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 业务异常：HTTP 200 + 业务码（前端 request.js 以 body.code 判定业务结果）；data 可附带 conflicts[] 等 */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Object> handleBiz(BizException e, jakarta.servlet.http.HttpServletRequest request) {
        if (e.getCode() == 403 || e.getCode() == 1403) {
            appendBlocked(e, request);
        }
        ApiResponse<Object> resp = ApiResponse.error(e.getCode(), e.getMessage());
        if (e.getData() != null) {
            resp.setData(e.getData());
        }
        return resp;
    }

    /** 越权拒绝路径 BLOCKED 审计（spec §5.2）；落库失败不影响错误响应。 */
    private void appendBlocked(BizException e, jakarta.servlet.http.HttpServletRequest request) {
        try {
            com.cqwlw.maintenance.entity.SysAuditLog a = new com.cqwlw.maintenance.entity.SysAuditLog();
            a.actorId = (String) request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_EMP_ID);
            a.actorRole = (String) request.getAttribute(com.cqwlw.maintenance.auth.AuthInterceptor.ATTR_ROLE);
            String action = request.getMethod() + " " + request.getRequestURI();
            a.action = action.length() > 64 ? action.substring(0, 64) : action;
            a.result = "BLOCKED";
            a.reason = e.getMessage();
            a.ip = request.getRemoteAddr();
            auditLogService.append(a);
        } catch (RuntimeException ex) {
            log.warn("BLOCKED 审计落库失败（不影响主流程）: {}", ex.getMessage());
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("参数错误");
        return ApiResponse.error(400, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return ApiResponse.error(400, "请求体格式错误");
    }

    /**
     * 路由未命中（含空路径段，如 /work-orders//checkin）：客户端错误须回 404，
     * 不能被兜底 handler 报成 500 服务内部错误（掩盖真实原因、误导排障）。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(Exception e) {
        return ApiResponse.error(404, "接口不存在");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ApiResponse.error(405, "请求方法不支持");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ApiResponse.error(500, "服务内部错误");
    }
}
