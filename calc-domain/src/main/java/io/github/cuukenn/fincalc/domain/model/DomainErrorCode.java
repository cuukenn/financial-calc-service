package io.github.cuukenn.fincalc.domain.model;

import io.github.cuukenn.fincalc.common.error.ErrorCode;

/**
 * Error codes raised by the domain model, following the {@code FCS-DOM-<4 digits>} convention.
 *
 * <p>Each message template embeds its own code so that the formatted
 * {@link io.github.cuukenn.fincalc.common.error.BusinessException} message always carries the stable code.
 */
public enum DomainErrorCode implements ErrorCode {

    PRINCIPAL_MUST_BE_POSITIVE("FCS-DOM-1001", "FCS-DOM-1001: 租赁本金必须为正数: %s"),

    NOMINAL_RATE_OUT_OF_RANGE("FCS-DOM-1002", "FCS-DOM-1002: 名义年利率必须在 [0, 1] 内: %s"),

    PERIODS_MUST_BE_POSITIVE("FCS-DOM-1003", "FCS-DOM-1003: 租期期数必须不小于 1: %s"),

    COMMENCE_DATE_REQUIRED("FCS-DOM-1004", "FCS-DOM-1004: 起租日不得为空"),

    FIRST_RENT_DATE_BEFORE_COMMENCE("FCS-DOM-1005", "FCS-DOM-1005: 首期租金日不得早于起租日: %s"),

    IRREGULAR_PERIODS_INVALID("FCS-DOM-1006", "FCS-DOM-1006: 不规则频率必须提供显式期间且每项 start < end: %s"),

    BALLOON_RESIDUAL_OUT_OF_RANGE("FCS-DOM-1007",
            "FCS-DOM-1007: BALLOON_IN_RENT 模式下残值必须满足 0 <= 残值 < 本金: %s"),

    EXPLICIT_RENTS_INVALID("FCS-DOM-1008",
            "FCS-DOM-1008: 显式租金为空或长度与期数不符: %s"),

    /** Same stable code as {@link #EXPLICIT_RENTS_INVALID}; the cross-method failure reads on its own. */
    EXPLICIT_RENTS_CROSS_METHOD("FCS-DOM-1008",
            "FCS-DOM-1008: 非显式租金方式不得携带 explicitRents（method=%s）"),

    EXPLICIT_RENTS_MUST_BE_POSITIVE("FCS-DOM-1009",
            "FCS-DOM-1009: 显式租金必须为正: %s"),

    PERIODS_OUT_OF_RANGE("FCS-DOM-1010",
            "FCS-DOM-1010: 租期期数不得超过硬上限 1200（月频 100 年）: %s"),

    FEE_AMOUNT_INVALID("FCS-DOM-1011",
            "FCS-DOM-1011: 费用金额不得为空且必须不小于 0: %s"),

    DEPOSIT_MUST_BE_NON_NEGATIVE("FCS-DOM-1012",
            "FCS-DOM-1012: 保证金必须不小于 0: %s"),

    ASSET_VALUE_OUT_OF_RANGE("FCS-DOM-1013",
            "FCS-DOM-1013: 资产价值必须大于 0 且不小于首付款: %s"),

    DOWN_PAYMENT_EXCEEDS_ASSET_VALUE("FCS-DOM-1014",
            "FCS-DOM-1014: 首付款不得超过资产价值: 首付 %s / 价值 %s"),

    NON_NEGATIVE_AMOUNT("FCS-DOM-1015",
            "FCS-DOM-1015: %s 不得为负: %s"),

    PRINCIPAL_NOT_BALANCED("FCS-DOM-1016",
            "FCS-DOM-1016: 融资额须等于 资产价值 + 初始直接费用 - 首付款: %s"),

    PERIOD_SEQUENCE_INVALID("FCS-DOM-2001",
            "FCS-DOM-2001: 期次日期必须严格递增且 start < end: 第 %s 期 [%s, %s]"),

    NON_UNIFORM_PERIOD_RATE("FCS-DOM-2002",
            "FCS-DOM-2002: 等额本息要求各期利率一致: 第 %s 期 %s 与首期 %s 不一致"),

    NEGATIVE_PRINCIPAL_DUE("FCS-DOM-2003",
            "FCS-DOM-2003: 第 %s 期本金为负: %s"),

    UNSUPPORTED_PAYMENT_TIMING("FCS-DOM-2004",
            "FCS-DOM-2004: 该算法暂不支持付款时点: %s");

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
