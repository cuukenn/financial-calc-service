package io.github.cuukenn.fincalc.server.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.cuukenn.fincalc.server.error.ServerErrorCode;

/**
 * The unified R77 error body: {@code {"code": "FCS-…", "message": …}} — same shape for HTTP
 * 400 (business failures) and HTTP 500 (system failures).
 */
public final class ApiError {

    private final String code;
    private final String message;

    public ApiError(@JsonProperty("code") String code,
            @JsonProperty("message") String message) {
        this.code = code;
        this.message = message;
    }

    /** Builds an error body from a {@link ServerErrorCode} without further formatting. */
    public static ApiError of(ServerErrorCode errorCode) {
        return new ApiError(errorCode.code(), errorCode.message());
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
