package io.github.cuukenn.fincalc.domain.model;

/**
 * Whose books the cash flows are written on (design document §9.4).
 *
 * <p>The lessor view funds the principal and receives the rents; the lessee view is its exact
 * mirror — the same dates, types and positive amounts with every {@link CashFlowDirection}
 * flipped. IRR is insensitive to flipping every sign, NPV changes sign only.
 */
public enum CashFlowPerspective {

    /** Lessor / funder: principal out, rents in. Default. */
    LESSOR,

    /** Lessee: principal in, rents out. */
    LESSEE
}
