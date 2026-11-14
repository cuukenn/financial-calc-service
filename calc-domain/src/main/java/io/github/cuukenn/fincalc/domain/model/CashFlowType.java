package io.github.cuukenn.fincalc.domain.model;

/**
 * Business kind of a {@link CashFlow} event.
 */
public enum CashFlowType {
    PRINCIPAL,
    RENT,
    FEE,
    RESIDUAL,
    DEPOSIT,

    /**
     * Interest the deposit earns while held, paid out at settlement as its own row — never folded
     * into rent or the deposit principal leg (AGENTS.md §3.1 第 11 条；form-field-gaps ticket 03:
     * 保证金计息时利息单独成行并标注口径）.
     */
    DEPOSIT_INTEREST
}
