package io.github.cuukenn.fincalc.domain.model;

/**
 * Options that steer one calculation run without changing the lease element itself.
 */
public final class CalculationOptions {

    // R73: the rate change policy is a contract term and lives on LeaseElement (like
    // residualHandling); it was never read from these options, so it has no field here.

    private final boolean allowPartialRuleSet;
    private final CashFlowBasis cashFlowBasis;

    public CalculationOptions(boolean allowPartialRuleSet, CashFlowBasis cashFlowBasis) {
        this.allowPartialRuleSet = allowPartialRuleSet;
        this.cashFlowBasis = cashFlowBasis == null ? CashFlowBasis.INCLUDE_FEES : cashFlowBasis;
    }

    /** (no partial rule sets, include fees in cash flows). */
    public static CalculationOptions defaults() {
        return new CalculationOptions(false, CashFlowBasis.INCLUDE_FEES);
    }

    public boolean allowPartialRuleSet() {
        return allowPartialRuleSet;
    }

    public CashFlowBasis cashFlowBasis() {
        return cashFlowBasis;
    }
}
