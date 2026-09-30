package com.cqwlw.maintenance.common;

/**
 * 业务异常：code 与前端 constants/index.js ERROR_CODES 及 docs/04 A.0.1 码表对齐。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
