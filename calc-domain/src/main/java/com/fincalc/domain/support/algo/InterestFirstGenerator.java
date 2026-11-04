package com.fincalc.domain.support.algo;

import java.math.BigDecimal;

import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.RepaymentMethodId;
import com.fincalc.domain.support.AbstractAmortizationGenerator;

/**
 * Interest-first schedule: every period pays interest only and the whole outstanding principal is
 * repaid with the final rent.
 *
 * <p>Unlike the two amortizing methods this shape does not care when the rent falls due — the
 * accrual window is the period either way — so both {@code IN_ARREARS} and {@code IN_ADVANCE} are
 * supported.
 *
 * <p>Under a balloon residual the final period repays {@code principal - residual} and the residual
 * stays outstanding at maturity, matching the conservation contract of
 * {@link AbstractAmortizationGenerator}.
 */
public final class InterestFirstGenerator extends AbstractAmortizationGenerator {

    @Override
    public String code() {
        return "INTEREST_FIRST";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean supports(CalculationContext context) {
        return context.element().method() == RepaymentMethodId.INTEREST_FIRST;
    }

    @Override
    protected Allocation allocate(AllocationInput input, AmortizationState state) {
        BigDecimal principal = input.lastPeriod()
                ? input.remainingPrincipal().subtract(residualOf(input.element()))
                : BigDecimal.ZERO;
        return new Allocation(principal, input.accruedInterest());
    }
}
