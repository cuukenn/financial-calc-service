package io.github.cuukenn.fincalc.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of {@code GET /api/v1/algorithms} (Plan 2 Task 3): a repayment method code of
 * {@code RepaymentMethodId} plus its human-readable Chinese description.
 */
@Schema(description = "一种内置还款算法：code 为 RepaymentMethodId 的枚举名，可直接用作 "
        + "POST /api/v1/calculations 请求中的 method 字段")
public final class AlgorithmDto {

    private final String code;
    private final String description;

    public AlgorithmDto(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @Schema(description = "还款方式 code（RepaymentMethodId 枚举名），如 EQUAL_INSTALLMENT",
            example = "EQUAL_INSTALLMENT", requiredMode = Schema.RequiredMode.REQUIRED)
    public String getCode() {
        return code;
    }

    @Schema(description = "该算法的中文说明", example = "等额租金（年金）：每期租金相等，末期尾差归入最后一期",
            requiredMode = Schema.RequiredMode.REQUIRED)
    public String getDescription() {
        return description;
    }
}
