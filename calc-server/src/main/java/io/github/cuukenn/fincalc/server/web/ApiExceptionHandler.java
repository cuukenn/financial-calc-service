package io.github.cuukenn.fincalc.server.web;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.error.SystemException;
import io.github.cuukenn.fincalc.server.error.ServerErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
 *   <li>malformed JSON / bean validation → HTTP 400 with {@code FCS-SRV-1001} (请求体非法).</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Expected, caller-actionable failures (R77): stable FCS code, HTTP 400. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> onBusinessException(BusinessException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError(e.errorCode().code(), e.getMessage()));
    }

    /** Unexpected engine failures (R77): HTTP 500, generic server-side code, no internals. */
    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiError> onSystemException(SystemException e) {
        log.error("calculation failed with a system error", e);
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
}
