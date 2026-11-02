package com.fincalc.domain.model;

/**
 * How the residual value (purchase option price) enters the calculation.
 */
public enum ResidualHandling {

    /** The residual value is settled separately at the end of the term. */
    SEPARATE,

    /** The residual value is rolled into the rent and settled with the last payment. */
    BALLOON_IN_RENT
}
