package io.github.cuukenn.fincalc.domain.calc;

import io.github.cuukenn.fincalc.common.error.ErrorCode;

/**
 * Error codes raised by the calculation orchestration, following the {@code FCS-CALC-<4 digits>}
 * convention. This enum is the single home of every code: the schedule validation codes of AGENTS.md
 * §3.4 (3001, 3002, 3004, 3008, 3009) as well as the codes raised while assembling inputs and
 * outputs. Validators raise through {@code ValidationIssue}s and reference the constants registered
 * here instead of spelling a {@code FCS-CALC-*} literal of their own; with 3001, 3002 and 3004
 * backfilled, the 3001–3005 block is complete in this enum.
 *
 * <p><b>Messages.</b> A §3.4 finding carries measured values and reaches the caller as a
 * {@code ValidationIssue}, which formats nothing: the raiser composes {@code code() + ": " + values}
 * — the pattern 3008/3009 already use — and this enum keeps only the static summary. A {@code %s}
 * template suits the codes below that are thrown as a {@code BusinessException} (3006, 3007 are
 * formatted by the exception constructor), but not these: rendering a template into a
 * {@code ValidationIssue} would need a second formatting step at every raising site, and one missed
 * argument would change today's message. Concatenation keeps it byte-for-byte.
 */
public enum CalcErrorCode implements ErrorCode {

    /**
     * AGENTS.md §3.4 校验一：Σ本金 = 合同本金 − 并入租金的残值（残值单独收取时为合同本金）不成立，
     * 误差 ≥ 0.01 元（补充口径规则 2 的比较容差）。Raised by
     * {@code PrincipalConservationValidator} and, while the plan is built, by
     * {@code AbstractAmortizationGenerator#checkConservation}; the finding embeds the two sums, so
     * the message is composed at the raising site.
     */
    PRINCIPAL_CONSERVATION_BROKEN("FCS-CALC-3001",
            "FCS-CALC-3001: 本金合计与应还本金不一致"),

    /**
     * AGENTS.md §3.4 校验二：末期余额 = 残值（并入租金时为 BALLOON，单独收取时为 0）不成立，误差
     * ≥ 0.01 元（补充口径规则 2 的比较容差）。Raised by {@code TailBalanceValidator} and by
     * {@code AbstractAmortizationGenerator#checkConservation}; the finding embeds the closing balance
     * and its expectation, so the message is composed at the raising site.
     */
    TAIL_BALANCE_MISMATCH("FCS-CALC-3002",
            "FCS-CALC-3002: 末期余额与预期不一致"),

    /**
     * The design document's 期次日期规则 family: a period window that is empty, does not start where
     * the previous one ended, or a period number that does not ascend. One code covers the family
     * because the chain reports at most one finding per code and period; {@code
     * DateMonotonicValidator} names every rule a period breaks inside that single finding, so the
     * message is composed at the raising site.
     */
    PERIOD_DATE_ORDER_INVALID("FCS-CALC-3004",
            "FCS-CALC-3004: 期次日期非单调"),

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
