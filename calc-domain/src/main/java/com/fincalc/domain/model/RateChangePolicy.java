package com.fincalc.domain.model;

/**
 * How the schedule reacts when the nominal rate changes mid-term.
 */
public enum RateChangePolicy {

    /** Recompute the affected payments from the change date onwards. */
    RESOLVE,

    /** Keep the agreed rent unchanged; absorb the difference in the tail. */
    FIXED_RENT,

    /** Let the difference drift into the closing balance. */
    DRIFT
}
