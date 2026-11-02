package com.fincalc.domain.model;

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

    /** Schedule driven by a compiled rule set rather than a fixed formula. */
    RULE_DRIVEN
}
