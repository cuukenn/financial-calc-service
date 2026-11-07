package io.github.cuukenn.fincalc.domain.support.algo;

import java.math.BigDecimal;
import java.util.List;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.DomainErrorCode;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import io.github.cuukenn.fincalc.domain.support.AbstractAmortizationGenerator;
import io.github.cuukenn.fincalc.domain.support.PmtSolver;

/**
 * Equal installment (annuity) schedule: every period carries the same rent, with interest on
 * the opening balance and principal as the remainder.
 *
 * <p>The level rent is solved once in {@link #prepare} by {@link PmtSolver}: closed form when the
 * day-count convention gives every period the same rate, bisection over the schedule's own windows
 * when it does not. Every period then allocates {@code pmt - accruedInterest} to principal; the
 * final period absorbs whatever principal is left so the schedule closes exactly.
 */
public final class EqualInstallmentGenerator extends AbstractAmortizationGenerator {

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
        DayCountConvention dayCount = context.dayCount();
        BigDecimal principal = money(element.principal(), element);
        BigDecimal residual = element.outstandingResidual();

        if (PmtSolver.isConstantPeriodRate(dayCount)) {
            BigDecimal rate = periodRate(element, periods.get(0), element.nominalRate(), dayCount);
            requireUniformRate(context, periods, rate);
            state.pmt(PmtSolver.closedForm(principal, rate, periods.size(), residual));
            state.solver("CLOSED_FORM");
            return;
        }
        state.pmt(PmtSolver.iterative(principal, periods, dayCount, element.nominalRate(), residual));
        state.solver("ITERATIVE");
    }

    /**
     * Guards the closed-form path: the annuity formula assumes one periodic rate. A stub period
     * that breaks rate uniformity fails fast, which is better than emitting a schedule with
     * negative balances that still passes the conservation check. Conventions that make every
     * period's rate differ by design are priced iteratively instead.
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
            principal = input.remainingPrincipal().subtract(input.element().outstandingResidual());
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
