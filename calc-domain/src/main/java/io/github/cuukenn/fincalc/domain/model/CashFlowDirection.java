package io.github.cuukenn.fincalc.domain.model;

/**
 * Sign convention of a {@link CashFlow}: amounts are always stored positive, the direction
 * carries the sign, so presentation layers never have to guess.
 */
public enum CashFlowDirection {
    INFLOW,
    OUTFLOW
}
