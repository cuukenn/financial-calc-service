package io.github.cuukenn.fincalc.domain.model;

/**
 * How a fee is collected.
 */
public enum FeeChargeMode {

    /** Charged once, upfront. */
    UPFRONT,

    /** Deducted from the financed principal before amortization. */
    DEDUCT_FROM_PRINCIPAL,

    /** Amortized into the periodic payments. */
    AMORTIZED
}
