package io.github.cuukenn.fincalc.domain.model;

/**
 * Identifier of the built-in amortization algorithm that generates the schedule.
 */
public enum RepaymentMethodId {

    /** Equal periodic installments (等额本息). */
    EQUAL_INSTALLMENT,

    /** Equal principal repayments with declining interest (等额本金). */
    EQUAL_PRINCIPAL,

    /** Interest paid first, principal at the end (先息后本). */
    INTEREST_FIRST,

    /** Principal paid first (先本后息). */
    PRINCIPAL_FIRST,

    /** 到期一次还本付息：本金与累计利息均在到期日一次性结清（bullet loan）。 */
    BULLET_LOAN,

    /** Schedule driven by a compiled rule set rather than a fixed formula. */
    RULE_DRIVEN,

    /** 显式逐期租金（04_现金流模式）— 租金给定，引擎反推本息拆分. */
    EXPLICIT_RENT
}
