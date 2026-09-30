package com.cqwlw.maintenance.common;

/** 业务异常：由 GlobalExceptionHandler 转为统一 ApiResponse 结构。 */
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
