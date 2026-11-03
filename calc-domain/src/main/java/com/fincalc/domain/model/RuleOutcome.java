package com.fincalc.domain.model;

import java.math.BigDecimal;

/**
 * Immutable outcome of resolving a rule for one period. Every field except {@code matched}
 * may be {@code null}, meaning "inherit the built-in behaviour".
 */
public final class RuleOutcome {

    private static final RuleOutcome NONE = new RuleOutcome(false, null, null, null,
            null, null, null, null, null, null);

    private final boolean matched;
    private final String ruleId;
    private final BigDecimal annualRate;
    private final String rateBase;
    private final PrincipalPlanKind principalPlan;
    private final BigDecimal principalValue;
    private final String principalSequence;
    private final InterestTreatmentKind interestTreatment;
    private final SpecialActionKind specialAction;
    private final String actionParam;

    public RuleOutcome(boolean matched, String ruleId, BigDecimal annualRate, String rateBase,
            PrincipalPlanKind principalPlan, BigDecimal principalValue, String principalSequence,
            InterestTreatmentKind interestTreatment, SpecialActionKind specialAction,
            String actionParam) {
        this.matched = matched;
        this.ruleId = ruleId;
        this.annualRate = annualRate;
        this.rateBase = rateBase;
        this.principalPlan = principalPlan;
        this.principalValue = principalValue;
        this.principalSequence = principalSequence;
        this.interestTreatment = interestTreatment;
        this.specialAction = specialAction;
        this.actionParam = actionParam;
    }

    /** Outcome for a period not covered by any rule. */
    public static RuleOutcome none() {
        return NONE;
    }

    public boolean matched() {
        return matched;
    }

    public String ruleId() {
        return ruleId;
    }

    public BigDecimal annualRate() {
        return annualRate;
    }

    public String rateBase() {
        return rateBase;
    }

    public PrincipalPlanKind principalPlan() {
        return principalPlan;
    }

    public BigDecimal principalValue() {
        return principalValue;
    }

    public String principalSequence() {
        return principalSequence;
    }

    public InterestTreatmentKind interestTreatment() {
        return interestTreatment;
    }

    public SpecialActionKind specialAction() {
        return specialAction;
    }

    public String actionParam() {
        return actionParam;
    }
}
