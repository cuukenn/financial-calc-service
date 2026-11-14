package io.github.cuukenn.fincalc.domain.model;

/**
 * When the deposit is collected (保证金的收取时点）.
 */
public enum DepositTiming {

    /** At the commencement date (t0), the v1 posture — collected alongside the financed principal. */
    UPFRONT,

    /** With the first rent, on the first period's rent payment date. */
    WITH_FIRST_RENT
}
