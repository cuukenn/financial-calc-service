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
 *       with a message that leaks no internals;</li>
 *   <li>{@link #METHOD_NOT_SUPPORTED} — raised by {@code ApiExceptionHandler} when no handler
 *       exists for the request's HTTP method on a mapped path (HTTP 405);</li>
 *   <li>{@link #MEDIA_TYPE_NOT_SUPPORTED} — raised by {@code ApiExceptionHandler} when the
 *       request's Content-Type matches no consumable media type (HTTP 415);</li>
 *   <li>{@link #NO_HANDLER} — raised by {@code ApiExceptionHandler} when no handler exists for
 *       the request path (HTTP 404; needs {@code spring.mvc.throw-exception-if-no-handler-found}
 *       + {@code spring.web.resources.add-mappings: false} so the DispatcherServlet throws
 *       {@code NoHandlerFoundException} into the advice instead of Boot's default
 *       {@code /error} body).</li>
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
    INTERNAL_ERROR("FCS-SRV-1002", "FCS-SRV-1002: 系统内部错误"),

    /**
     * HTTP-method check of {@code ApiExceptionHandler}: the path is mapped but the request's
     * method has no handler ({@code HttpRequestMethodNotSupportedException}); caller error,
     * HTTP 405, message names the offending method.
     */
    METHOD_NOT_SUPPORTED("FCS-SRV-1003", "FCS-SRV-1003: 请求方法不支持: %s"),

    /**
     * Content-Type check of {@code ApiExceptionHandler}: the request's media type matches no
     * consumable type of the mapped handler ({@code HttpMediaTypeNotSupportedException});
     * caller error, HTTP 415, message names the offending media type.
     */
    MEDIA_TYPE_NOT_SUPPORTED("FCS-SRV-1004", "FCS-SRV-1004: 请求媒体类型不支持: %s"),

    /**
     * Routing check of {@code ApiExceptionHandler}: no handler exists for the request path
     * ({@code NoHandlerFoundException}, thrown by the DispatcherServlet under
     * {@code spring.mvc.throw-exception-if-no-handler-found: true}); caller error, HTTP 404,
     * message names the requested path.
     */
    NO_HANDLER("FCS-SRV-1005", "FCS-SRV-1005: 请求路径不存在: %s"),

    /**
     * §15：请求体大小上限（design §15 的 strict 1MB JSON filter，form-field-gaps 收口轮
     * 2026-11-14 实施）。Tomcat 的 form/multipart 上限不覆盖普通 JSON 体，过滤器按
     * Content-Length 与流式计数双路径拦（HTTP 413，统一错误体）。
     */
    REQUEST_BODY_TOO_LARGE("FCS-SRV-1006", "FCS-SRV-1006: 请求体超过上限: %s");

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
