package com.fincalc.domain.model;

/**
 * One-off business action attached to a rule row. v1 supports only the first three; the
 * remaining values are reserved so the compiler can reject them with a stable error code.
 */
public enum SpecialActionKind {

    NONE,

    /** Spread a fee over the covered periods. */
    FEE_AMORTIZE,

    /** Offset the deposit against rent or principal. */
    DEPOSIT_OFFSET,

    /** Reserved for v2: early repayment. */
    PREPAY,

    /** Reserved for v2: lease extension. */
    EXTEND
}
