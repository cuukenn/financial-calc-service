package io.github.cuukenn.fincalc.server.web;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.error.SystemException;
import io.github.cuukenn.fincalc.server.error.ServerErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * The unified R77 exception mapping of the REST API:
 *
 * <ul>
 *   <li>{@link BusinessException} → HTTP 400 with {@code {"code": "FCS-…", "message": …}}.
 *       This covers the request-shaped engine codes too: FCS-ENG-1002 (unknown repayment
 *       method, raised by the request assembler) and FCS-ENG-1003 (RULE_DRIVEN without a rule
 *       set, raised by the facade) arrive as business exceptions and so answer 400, as do the
 *       domain's FCS-DOM-* validation failures and the rule compiler's
 *       {@code RuleValidationException};</li>
 *   <li>{@link SystemException} → HTTP 500 with the same body shape and a server-side
 *       FCS-SRV code that leaks no internals;</li>
 *   <li>malformed JSON / bean validation → HTTP 400 with {@code FCS-SRV-1001} (请求体非法);</li>
 *   <li>routing failures → caller-error status codes in the same body shape: HTTP 405
 *       {@code FCS-SRV-1003} (方法不支持), HTTP 415 {@code FCS-SRV-1004} (媒体类型不支持),
 *       HTTP 404 {@code FCS-SRV-1005} (请求路径不存在, needs
 *       {@code spring.mvc.throw-exception-if-no-handler-found} so the DispatcherServlet raises
 *       {@code NoHandlerFoundException} here instead of Boot's default {@code /error} body).</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Expected, caller-actionable failures (R77): stable FCS code, HTTP 400. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> onBusinessException(BusinessException e) {
        log.warn("calculation rejected as a business failure: {} ({})", e.getMessage(),
                e.errorCode().code());
        return ResponseEntity.badRequest()
                .body(new ApiError(e.errorCode().code(), e.getMessage()));
    }

    /** Unexpected engine failures (R77): HTTP 500, generic server-side code, no internals. */
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiError> onSystemException(SystemException e) {
        log.error("calculation failed with a system error", e);
        return internalError(e);
    }

    /**
     * Catch-all for anything uncaught (R77's unified body must hold for every failure, so no
     * Spring default {@code /error} body ever leaks): unexpected {@code RuntimeException}s from
     * deep layers (e.g. an {@code IllegalArgumentException}) and infrastructure faults answer
     * HTTP 500 with the fixed generic message below — never {@code e.toString()}, which could
     * carry internal details. The more specific {@code BusinessException}/{@code
     * SystemException} handlers above keep precedence.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUncaught(Exception e) {
        log.error("calculation failed with an uncaught error", e);
        return internalError(e);
    }

    private ResponseEntity<ApiError> internalError(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ServerErrorCode.INTERNAL_ERROR));
    }

    /** Malformed JSON or a field Jackson cannot bind (R77: HTTP 400, FCS-SRV-1001). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> onUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ServerErrorCode.MALFORMED_REQUEST_BODY.code(),
                        String.format(ServerErrorCode.MALFORMED_REQUEST_BODY.message(),
                                "JSON 语法错误或字段类型不匹配")));
    }

    /** Bean-validation failures, once validation annotations enter the DTOs (R77: 400). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onValidationFailure(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ServerErrorCode.MALFORMED_REQUEST_BODY.code(),
                        String.format(ServerErrorCode.MALFORMED_REQUEST_BODY.message(),
                                "请求体校验失败")));
    }

    /** No handler for the request's HTTP method on a mapped path: 405, FCS-SRV-1003. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> onMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.debug("request method not supported: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError(ServerErrorCode.METHOD_NOT_SUPPORTED.code(),
                        String.format(ServerErrorCode.METHOD_NOT_SUPPORTED.message(),
                                e.getMethod())));
    }

    /** Request Content-Type matches no consumable media type: 415, FCS-SRV-1004. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> onMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.debug("request media type not supported: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError(ServerErrorCode.MEDIA_TYPE_NOT_SUPPORTED.code(),
                        String.format(ServerErrorCode.MEDIA_TYPE_NOT_SUPPORTED.message(),
                                String.valueOf(e.getContentType()))));
    }

    /**
     * No handler for the request path: 404, FCS-SRV-1005. Reaches this advice only under
     * {@code spring.mvc.throw-exception-if-no-handler-found: true} +
     * {@code spring.web.resources.add-mappings: false} (application.yml) — otherwise Boot's
     * default {@code /error} body ({@code {timestamp,status,error,path}}) would leak instead.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> onNoHandlerFound(NoHandlerFoundException e) {
        log.debug("no handler for request: {}", e.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(ServerErrorCode.NO_HANDLER.code(),
                        String.format(ServerErrorCode.NO_HANDLER.message(),
                                e.getRequestURL())));
    }
}
