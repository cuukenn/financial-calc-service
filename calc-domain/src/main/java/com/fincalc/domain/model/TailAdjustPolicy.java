package com.fincalc.domain.model;

/**
 * Where a rounding difference (expected total minus computed total) is absorbed.
 */
public enum TailAdjustPolicy {

    /** Adjust the last installment by the difference. */
    LAST_PERIOD,

    /** Adjust the first installment by the difference. */
    FIRST_PERIOD,

    /** Spread the difference across all installments proportionally. */
    PROPORTIONAL
}
