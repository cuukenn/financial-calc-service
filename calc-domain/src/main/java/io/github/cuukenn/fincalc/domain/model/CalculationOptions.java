package io.github.cuukenn.fincalc.domain.model;

/**
 * Options that steer one calculation run without changing the lease element itself.
 */
public final class CalculationOptions {

    private final boolean allowPartialRuleSet;
    private final RateChangePolicy rateChangePolicy;
    private final CashFlowBasis cashFlowBasis;

    public CalculationOptions(boolean allowPartialRuleSet, RateChangePolicy rateChangePolicy,
            CashFlowBasis cashFlowBasis) {
        this.allowPartialRuleSet = allowPartialRuleSet;
        this.rateChangePolicy = rateChangePolicy == null ? RateChangePolicy.RESOLVE : rateChangePolicy;
        this.cashFlowBasis = cashFlowBasis == null ? CashFlowBasis.INCLUDE_FEES : cashFlowBasis;
    }

    /** (no partial rule sets, resolve rate changes, include fees in cash flows). */
    public static CalculationOptions defaults() {
        return new CalculationOptions(false, RateChangePolicy.RESOLVE, CashFlowBasis.INCLUDE_FEES);
    }

    public boolean allowPartialRuleSet() {
        return allowPartialRuleSet;
    }

    public RateChangePolicy rateChangePolicy() {
        return rateChangePolicy;
    }

    public CashFlowBasis cashFlowBasis() {
        return cashFlowBasis;
    }
}
