package io.github.cuukenn.fincalc.domain.calc;

import io.github.cuukenn.fincalc.common.error.ErrorCode;

/**
 * Error codes raised by the calculation orchestration, following the {@code FCS-CALC-<4 digits>}
 * convention. The codes assigned to schedule validation (3001, 3002, 3004) live in the validators
 * themselves; this enum carries the codes raised while assembling inputs and outputs, plus the
 * codes of the §3.4 identity validators (3008, 3009): those validators raise through
 * {@code ValidationIssue}s and reference the constants registered here.
 */
public enum CalcErrorCode implements ErrorCode {

    /** VAT-split cash flows are a v2 feature; the basis is rejected instead of degraded. */
    VAT_SPLIT_NOT_SUPPORTED("FCS-CALC-3005",
            "FCS-CALC-3005: 价税分离现金流口径 v1 暂不支持"),

    /**
     * §9.5's defined no-root marker (「IRR 不收敛」). The metric layer records it as the
     * present-but-null marker; here it guards a caller handing an uncomputable yield to the
     * effective-interest table — a rate that does not exist can price nothing.
     */
    IRR_NOT_CONVERGED("FCS-CALC-3003",
            "FCS-CALC-3003: IRR 不收敛，无法建立实际利率法摊销表"),

    /** §9.7: the effective-interest table's final closing net investment misses the schedule's terminal balance. */
    EFFECTIVE_INTEREST_TABLE_NOT_CLOSING("FCS-CALC-3006",
            "FCS-CALC-3006: 实际利率法摊销表末期净投资未收敛: %s"),

    /**
     * §9.7 extension (design ledger ruling R48): the effective-interest rate the table is built
     * with must be a legal rate. Codes 3001–3005 are taken; 3006 is reserved for the non-closing
     * table, so an illegal rate gets its own code instead of borrowing it.
     */
    EFFECTIVE_INTEREST_RATE_INVALID("FCS-CALC-3007",
            "FCS-CALC-3007: 实际利率法要求合法实际利率: %s"),

    /**
     * AGENTS.md §3.4 校验三：逐期恒等式「期初余额 − 本期本金 = 期末余额」在某期被破坏，误差
     * ≥ 0.01 元（补充口径规则 2 的比较容差）。Raised per period by
     * {@code PeriodIdentityValidator}.
     */
    PERIOD_IDENTITY_BROKEN("FCS-CALC-3008",
            "FCS-CALC-3008: 逐期恒等式不成立: 期初余额 − 本期本金 ≠ 期末余额"),

    /**
     * AGENTS.md §3.4 校验四：Σ租金 = Σ本金 + Σ利息（实付）+ Σ费用 + Σ税 不成立，误差
     * ≥ 0.01 元（补充口径规则 2 的比较容差）。Raised by {@code RentCompositionValidator}.
     */
    RENT_COMPOSITION_BROKEN("FCS-CALC-3009",
            "FCS-CALC-3009: 租金构成不成立: Σ租金 ≠ Σ本金 + Σ利息 + Σ费用 + Σ税");

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
