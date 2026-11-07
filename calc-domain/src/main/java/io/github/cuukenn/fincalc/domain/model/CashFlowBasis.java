package io.github.cuukenn.fincalc.domain.model;

/**
 * Which cash flows participate in the IRR / XIRR view.
 */
public enum CashFlowBasis {

    /** Rent and principal only. */
    CONTRACT,

    /** Rent, principal and upfront fees. */
    INCLUDE_FEES,

    /** Rent, fees and the refundable deposit. */
    INCLUDE_DEPOSIT,

    /** Reserved: split tax out of the rent. Rejected in v1. */
    VAT_SPLIT
}
