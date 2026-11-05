package com.fincalc.domain.calc;

import com.fincalc.common.error.ErrorCode;

/**
 * Error codes raised by the calculation orchestration, following the {@code FCS-CALC-<4 digits>}
 * convention. The codes assigned to schedule validation (3001, 3002, 3004) live in the validators
 * themselves; this enum carries the codes raised while assembling inputs and outputs.
 */
public enum CalcErrorCode implements ErrorCode {

    /** VAT-split cash flows are a v2 feature; the basis is rejected instead of degraded. */
    VAT_SPLIT_NOT_SUPPORTED("FCS-CALC-3005",
            "FCS-CALC-3005: 价税分离现金流口径 v1 暂不支持");

    private final String code;
    private final String message;

    CalcErrorCode(String code, String message) {
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
