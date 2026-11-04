package com.fincalc.domain.support.algo;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

import com.fincalc.common.error.BusinessException;
import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.DomainErrorCode;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.PeriodDate;
import com.fincalc.domain.model.RepaymentMethodId;
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
    private static final BigDecimal RATE_EPSILON = new BigDecimal("0.000000000001");

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
        return context.element().method() == RepaymentMethodId.EQUAL_INSTALLMENT
                && context.element().timing() == PaymentTiming.IN_ARREARS;
    }

    @Override
    protected void validate(LeaseElement element) {
        if (element.timing() != PaymentTiming.IN_ARREARS) {
            throw new BusinessException(DomainErrorCode.UNSUPPORTED_PAYMENT_TIMING,
                    element.timing());
        }
    }

    @Override
    protected void prepare(CalculationContext context, List<PeriodDate> periods,
            AmortizationState state) {
        LeaseElement element = context.element();
        int n = periods.size();
        BigDecimal principal = money(element.principal(), element);
        BigDecimal rate = periodRate(element, periods.get(0), element.nominalRate(),
                context.dayCount());
        requireUniformRate(context, periods, rate);

        if (rate.signum() == 0) {
            state.pmt(principal.subtract(residualOf(element)).divide(BigDecimal.valueOf(n), MC));
            return;
        }

        if (n == 1) {
            state.pmt(principal.multiply(BigDecimal.ONE.add(rate)).subtract(residualOf(element)));
            return;
        }

        BigDecimal growth = BigDecimal.ONE.add(rate).pow(n, MC);
        BigDecimal denominator = growth.subtract(BigDecimal.ONE);
        if (denominator.signum() == 0) {
            state.pmt(principal.subtract(residualOf(element)).divide(BigDecimal.valueOf(n), MC));
            return;
        }
        BigDecimal presentResidual = residualOf(element).divide(growth, MC);
        BigDecimal numerator = principal.subtract(presentResidual).multiply(rate).multiply(growth);
        state.pmt(numerator.divide(denominator, MC));
    }

    /**
     * The annuity formula assumes one periodic rate. A stub first period, an ACT convention or a
     * mid-term rate change breaks that assumption; failing fast is better than emitting a
     * schedule with negative balances that still passes the conservation check.
     */
    private static void requireUniformRate(CalculationContext context, List<PeriodDate> periods,
            BigDecimal firstRate) {
        for (int index = 1; index < periods.size(); index++) {
            PeriodDate period = periods.get(index);
            BigDecimal rate = context.element().nominalRate()
                    .multiply(context.dayCount().yearFraction(period.startDate(), period.endDate()));
            if (rate.subtract(firstRate).abs().compareTo(RATE_EPSILON) > 0) {
                throw new BusinessException(DomainErrorCode.NON_UNIFORM_PERIOD_RATE,
                        index + 1, rate.toPlainString(), firstRate.toPlainString());
            }
        }
    }

    @Override
    protected Allocation allocate(AllocationInput input, AmortizationState state) {
        BigDecimal principal;
        if (input.lastPeriod()) {
            principal = input.remainingPrincipal().subtract(residualOf(input.element()));
        } else {
            principal = state.pmt().subtract(input.accruedInterest());
        }
        BigDecimal rounded = money(principal, input.element());
        if (rounded.signum() < 0) {
            throw new BusinessException(DomainErrorCode.NEGATIVE_PRINCIPAL_DUE,
                    input.periodNo(), rounded.toPlainString());
        }
        return new Allocation(principal, input.accruedInterest());
    }

    private static BigDecimal money(BigDecimal value, LeaseElement element) {
        return value.setScale(element.rounding().scale(), element.rounding().mode());
    }
}
