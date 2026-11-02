package com.fincalc.common.error;

/** Error codes shared by every module. */
public enum CommonErrorCode implements ErrorCode {

    INVALID_ARGUMENT("FCS-COM-0001", "参数 %s 非法: %s"),

    NOT_FOUND("FCS-COM-0002", "资源不存在: %s"),

    STATE_CONFLICT("FCS-COM-0003", "状态冲突: %s (期望 %s)");

    private final String code;
    private final String message;

    CommonErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
