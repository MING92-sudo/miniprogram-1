package com.cqwlw.maintenance.common;

import com.cqwlw.maintenance.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MultipartException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 客户端错误映射回归（E2E 发现）：缺查询参数与非 multipart 上传原本落到兜底 handler，
 * 返回 500「服务内部错误」，把「调用方漏参」伪装成服务端故障。
 */
class GlobalExceptionHandlerClientErrorTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(AuditLogService.class));

    @Test
    void missingQueryParamIs400Not500() {
        ApiResponse<Void> resp = handler.handleMissingParam(
                new MissingServletRequestParameterException("planId", "String"));
        assertEquals(400, resp.getCode());
        assertEquals("缺少必填参数：planId", resp.getMessage());
    }

    @Test
    void nonMultipartUploadIs415Not500() {
        ApiResponse<Void> resp = handler.handleNotMultipart(new MultipartException("Current request is not a multipart request"));
        assertEquals(415, resp.getCode());
    }

    @Test
    void unsupportedMediaTypeIs415() {
        ApiResponse<Void> resp = handler.handleMediaType(new HttpMediaTypeNotSupportedException("text/plain"));
        assertEquals(415, resp.getCode());
    }
}
