package com.fincalc.common.error;

import java.util.Objects;

/**
 * Expected, user-facing failure carrying a stable {@link ErrorCode}.
 *
 * <p>The message is formatted once, at construction time, from the code's template and the given
 * arguments. Callers should treat this exception as part of the normal API contract rather than as a
 * programming error.
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Object[] args;

    /**
     * Creates a business exception.
     *
     * @param errorCode stable error code; never {@code null}
     * @param args      values for the {@code String.format} placeholders of
     *                  {@link ErrorCode#message()}
     */
    public BusinessException(ErrorCode errorCode, Object... args) {
        super(format(errorCode, args));
        this.errorCode = errorCode;
        this.args = args == null ? new Object[0] : args.clone();
    }

    /** The stable error code this failure was raised with. */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** The formatting arguments, as a defensive copy. */
    public Object[] args() {
        return args.clone();
    }

    private static String format(ErrorCode errorCode, Object[] args) {
        Objects.requireNonNull(errorCode, "errorCode");
        String template = Objects.requireNonNull(errorCode.message(), "errorCode.message()");
        if (args == null || args.length == 0) {
            return template;
        }
        return String.format(template, args);
    }
}
