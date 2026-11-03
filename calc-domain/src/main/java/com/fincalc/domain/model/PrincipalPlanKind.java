package com.fincalc.domain.model;

/**
 * How principal is repaid over the periods covered by a rule.
 */
public enum PrincipalPlanKind {

    /** Split the covered principal evenly across the covered periods. */
    EQUAL,

    /** Repay a fixed amount each period, given by {@code principalValue}. */
    FIXED,

    /** Repay a percentage of the contract principal, given by {@code principalValue}. */
    PERCENT,

    /** Follow the explicit sequence in {@code principalSequence}. */
    CUSTOM,

    /** Repay no principal in this period. */
    NONE,

    /** Let the selected built-in algorithm decide. */
    INHERIT
}
