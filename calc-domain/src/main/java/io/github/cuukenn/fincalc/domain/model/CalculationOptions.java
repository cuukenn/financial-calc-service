package io.github.cuukenn.fincalc.domain.model;

/**
 * Options that steer one calculation run without changing the lease element itself.
 */
public final class CalculationOptions {

    // R73: the rate change policy is a contract term and lives on LeaseElement (like
    // residualHandling); it was never read from these options, so it has no field here.

    private final boolean allowPartialRuleSet;
    private final CashFlowBasis cashFlowBasis;
    private final ProductProfile product;

    public CalculationOptions(boolean allowPartialRuleSet, CashFlowBasis cashFlowBasis) {
        this(allowPartialRuleSet, cashFlowBasis, ProductProfile.LEASE);
    }

    public CalculationOptions(boolean allowPartialRuleSet, CashFlowBasis cashFlowBasis,
            ProductProfile product) {
        this.allowPartialRuleSet = allowPartialRuleSet;
        this.cashFlowBasis = cashFlowBasis == null ? CashFlowBasis.INCLUDE_FEES : cashFlowBasis;
        this.product = product == null ? ProductProfile.LEASE : product;
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

    /**
     * Which business product this run is for; never {@code null} (defaults to
     * {@link ProductProfile#LEASE}). It selects additional validators only — the calculation core
     * is product-blind (R100).
     */
    public ProductProfile product() {
        return product;
    }
}
