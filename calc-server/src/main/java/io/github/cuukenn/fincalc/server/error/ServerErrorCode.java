package io.github.cuukenn.fincalc.server.error;

import io.github.cuukenn.fincalc.common.error.ErrorCode;

/**
 * Error codes raised by the calc-server web layer, following the {@code FCS-SRV-<4 digits>}
 * convention (house rule R57: a code enters this enum together with the code path that raises
 * it, and each constant names the check that raises it).
 *
 * <ul>
 *   <li>{@link #MALFORMED_REQUEST_BODY} — raised by {@code ApiExceptionHandler} when the
 *       request body cannot be read (malformed JSON) or a field fails Jackson binding
 *       (unknown enum value, wrong type); R77 maps this to HTTP 400;</li>
 *   <li>{@link #INTERNAL_ERROR} — raised by {@code ApiExceptionHandler} for unexpected
 *       failures ({@code SystemException} or anything uncaught); R77 maps this to HTTP 500
 *       with a message that leaks no internals.</li>
 * </ul>
 */
public enum ServerErrorCode implements ErrorCode {

    /**
     * Request-body readability check of {@code ApiExceptionHandler}: the body must be well-
     * formed JSON whose fields bind to the request DTO.
     */
    MALFORMED_REQUEST_BODY("FCS-SRV-1001", "FCS-SRV-1001: 请求体非法: %s"),

    /**
     * Unexpected-failure mapping of {@code ApiExceptionHandler}: anything the API contract does
     * not treat as a caller error is reported as an internal error without internal details.
     */
    INTERNAL_ERROR("FCS-SRV-1002", "FCS-SRV-1002: 系统内部错误");

    private final String code;
    private final String message;

    ServerErrorCode(String code, String message) {
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
