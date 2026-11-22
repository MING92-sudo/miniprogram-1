package com.cqwlw.maintenance.common;

public class BizException extends RuntimeException {

    private final int code;
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
