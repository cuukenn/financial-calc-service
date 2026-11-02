package com.fincalc.domain.model;

import com.fincalc.common.error.ErrorCode;

/**
 * Error codes raised by the domain model, following the {@code FCS-DOM-<4 digits>} convention.
 *
 * <p>Each message template embeds its own code so that the formatted
 * {@link com.fincalc.common.error.BusinessException} message always carries the stable code.
 */
public enum DomainErrorCode implements ErrorCode {

    PRINCIPAL_MUST_BE_POSITIVE("FCS-DOM-1001", "FCS-DOM-1001: 租赁本金必须为正数: %s"),

    NOMINAL_RATE_OUT_OF_RANGE("FCS-DOM-1002", "FCS-DOM-1002: 名义年利率必须在 [0, 1] 内: %s"),

    PERIODS_MUST_BE_POSITIVE("FCS-DOM-1003", "FCS-DOM-1003: 租期期数必须不小于 1: %s"),

    COMMENCE_DATE_REQUIRED("FCS-DOM-1004", "FCS-DOM-1004: 起租日不得为空"),

    FIRST_RENT_DATE_BEFORE_COMMENCE("FCS-DOM-1005", "FCS-DOM-1005: 首期租金日不得早于起租日: %s"),

    IRREGULAR_PERIODS_INVALID("FCS-DOM-1006", "FCS-DOM-1006: 不规则频率必须提供显式期间且每项 start < end: %s"),

    BALLOON_RESIDUAL_OUT_OF_RANGE("FCS-DOM-1007",
            "FCS-DOM-1007: BALLOON_IN_RENT 模式下残值必须满足 0 <= 残值 < 本金: %s");

    private final String code;
    private final String message;

    DomainErrorCode(String code, String message) {
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
