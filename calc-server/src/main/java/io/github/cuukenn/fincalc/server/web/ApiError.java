package io.github.cuukenn.fincalc.server.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.cuukenn.fincalc.server.error.ServerErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The unified R77 error body: {@code {"code": "FCS-…", "message": …}} — same shape for HTTP
 * 400 (business failures), HTTP 404/405/415 (routing failures) and HTTP 500 (system failures).
 */
@Schema(description = "统一错误体（R77）：所有失败（HTTP 400 业务失败 / HTTP 404 405 415 路由性"
        + "失败 / HTTP 500 系统失败）均返回该结构，无其他形态。code 为 FCS 错误码——清单见设计文档"
        + " §B 错误码草案（docs/superpowers/specs/2026-11-02-financial-calc-service-design.md）"
        + "及引擎实际扩充（FCS-DOM-1xxx 领域校验、FCS-ENG-1xxx 编排、FCS-RULE-2xxx 规则、"
        + "FCS-COM-0001 公共、FCS-SRV-1001~1005 服务层）")
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

    @Schema(description = "FCS 错误码：请求性错误（400）如 FCS-DOM-1001、FCS-ENG-1002/1003、"
            + "FCS-COM-0001、FCS-SRV-1001（请求体非法）；路由性错误如 FCS-SRV-1003（405 方法"
            + "不支持）、FCS-SRV-1004（415 媒体类型不支持）、FCS-SRV-1005（404 路径不存在）；"
            + "系统错误（500）固定为 FCS-SRV-1002",
            example = "FCS-DOM-1001", requiredMode = Schema.RequiredMode.REQUIRED)
    public String getCode() {
        return code;
    }

    @Schema(description = "面向调用方的错误说明（500 时不含内部细节）",
            example = "FCS-DOM-1001: 租赁本金必须为正数: 0",
            requiredMode = Schema.RequiredMode.REQUIRED)
    public String getMessage() {
        return message;
    }
}
