package com.cqwlw.maintenance.common;

/**
 * 业务异常：code 与前端 constants/index.js ERROR_CODES 及 docs/04 A.0.1 码表对齐。
 */
public class BizException extends RuntimeException {

    private final int code;
    /** 附带数据（如 1002 手机号互斥的 data.conflicts[]，docs/04 A.0.1 码表） */
    private final java.util.Map<String, Object> data;

    public BizException(int code, String message) {
        this(code, message, null);
    }

    public BizException(int code, String message, java.util.Map<String, Object> data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public java.util.Map<String, Object> getData() {
        return data;
    }
}
