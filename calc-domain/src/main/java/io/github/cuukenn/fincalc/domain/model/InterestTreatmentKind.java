package io.github.cuukenn.fincalc.domain.model;

/**
 * What happens to the interest accrued in a period.
 */
public enum InterestTreatmentKind {

    /** Pay the accrued interest in the same period. */
    PAY_AS_INCURRED,

    /** Add the accrued interest to the outstanding balance. */
    CAPITALIZE,

    /** Postpone the payment to a later period. */
    DEFER,

    /** Pay all deferred interest together at maturity. */
    SETTLE_AT_MATURITY,

    /** Do not charge interest for this period. */
    EXEMPT
}
