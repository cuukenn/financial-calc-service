package com.fincalc.domain.support.algo;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PeriodDate;
import com.fincalc.domain.model.RepaymentMethodId;
import com.fincalc.domain.model.ResidualHandling;
import com.fincalc.domain.support.AbstractAmortizationGenerator;

/**
 * Equal installment (annuity) schedule: every period carries the same rent, with interest on
 * the opening balance and principal as the remainder.
 *
 * <p>The level payment is solved once in {@link #prepare} from the first period's rate, then
 * every period allocates {@code pmt - accruedInterest} to principal. The final period absorbs
 * whatever principal is left so the schedule closes exactly.
 */
public final class EqualInstallmentGenerator extends AbstractAmortizationGenerator {

    private static final MathContext MC = MathContext.DECIMAL128;

    @Override
    public String code() {
        return "EQUAL_INSTALLMENT";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(CalculationContext context) {
        return context.element().method() == RepaymentMethodId.EQUAL_INSTALLMENT;
    }

    @Override
    protected void prepare(CalculationContext context, List<PeriodDate> periods,
            AmortizationState state) {
        LeaseElement element = context.element();
        int n = periods.size();
        BigDecimal principal = element.principal();
        BigDecimal rate = periodRate(element, periods.get(0), element.nominalRate(),
                context.dayCount());

        if (rate.signum() == 0) {
            state.pmt(principal.subtract(residualOf(element)).divide(BigDecimal.valueOf(n), MC));
            return;
        }

        if (n == 1) {
            state.pmt(principal.multiply(BigDecimal.ONE.add(rate)).subtract(residualOf(element)));
            return;
        }

        BigDecimal growth = BigDecimal.ONE.add(rate).pow(n, MC);
        BigDecimal presentResidual = residualOf(element).divide(growth, MC);
        BigDecimal numerator = principal.subtract(presentResidual).multiply(rate).multiply(growth);
        BigDecimal denominator = growth.subtract(BigDecimal.ONE);
        state.pmt(numerator.divide(denominator, MC));
    }

    @Override
    protected Allocation allocate(AllocationInput input, AmortizationState state) {
        BigDecimal principal;
        if (input.lastPeriod()) {
            principal = input.remainingPrincipal().subtract(residualOf(input.element()));
        } else {
            principal = state.pmt().subtract(input.accruedInterest());
        }
        return new Allocation(principal, input.accruedInterest());
    }

    /** Residual left outstanding at maturity; zero unless it is ballooned into the rent. */
    private static BigDecimal residualOf(LeaseElement element) {
        if (element.residualHandling() != ResidualHandling.BALLOON_IN_RENT) {
            return BigDecimal.ZERO;
        }
        return element.residualValue() == null ? BigDecimal.ZERO : element.residualValue();
    }
}
