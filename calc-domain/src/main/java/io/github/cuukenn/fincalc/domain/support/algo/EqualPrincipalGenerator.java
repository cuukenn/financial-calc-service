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
import io.github.cuukenn.fincalc.domain.support.AbstractAmortizationGenerator;

/**
 * Equal principal schedule: every period repays the same amount of principal, so the rent
 * declines as interest falls with the outstanding balance.
 *
 * <p>The per-period principal is rounded once in {@link #prepare}; the final period absorbs the
 * cent-level remainder so the schedule closes exactly.
 */
public final class EqualPrincipalGenerator extends AbstractAmortizationGenerator {

    @Override
    public String code() {
        return "EQUAL_PRINCIPAL";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean supports(CalculationContext context) {
        return context.element().method() == RepaymentMethodId.EQUAL_PRINCIPAL
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
    protected Allocation allocate(AllocationInput input, AmortizationState state) {
        BigDecimal principal;
        if (input.lastPeriod()) {
            principal = input.remainingPrincipal().subtract(input.element().outstandingResidual());
        } else {
            principal = state.pmt();
        }
        return new Allocation(principal, input.accruedInterest());
    }

    @Override
    protected void prepare(CalculationContext context, List<PeriodDate> periods,
            AmortizationState state) {
        LeaseElement element = context.element();
        BigDecimal principal = element.measuredPrincipal()
                .setScale(element.rounding().scale(), element.rounding().mode())
                .subtract(element.outstandingResidual());
        state.pmt(principal.divide(BigDecimal.valueOf(periods.size()),
                element.rounding().scale(), element.rounding().mode()));
    }
}
