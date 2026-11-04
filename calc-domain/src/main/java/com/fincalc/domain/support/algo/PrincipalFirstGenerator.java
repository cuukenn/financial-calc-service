package com.fincalc.domain.support.algo;

import java.math.BigDecimal;
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
 * Principal-first schedule: the principal is repaid in equal instalments while each period's
 * interest is accrued on the opening balance but left unpaid, accumulating in
 * {@link AmortizationState#deferredInterest()} until the final rent settles it in one payment.
 *
 * <p>Interest accrues on the outstanding principal only — deferred interest does not compound, and
 * it never enters the balance, so the balance line stays identical to an equal-principal schedule.
 *
 * <p>Like the other built-in methods this generator models rents due at the end of their period;
 * {@code IN_ADVANCE} is rejected rather than silently mispriced.
 */
public final class PrincipalFirstGenerator extends AbstractAmortizationGenerator {

    @Override
    public String code() {
        return "PRINCIPAL_FIRST";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public boolean supports(CalculationContext context) {
        return context.element().method() == RepaymentMethodId.PRINCIPAL_FIRST
                && context.element().timing() == PaymentTiming.IN_ARREARS;
    }

    @Override
    protected void validate(LeaseElement element) {
        if (element.timing() != PaymentTiming.IN_ARREARS) {
            throw new BusinessException(DomainErrorCode.UNSUPPORTED_PAYMENT_TIMING, element.timing());
        }
    }

    @Override
    protected void prepare(CalculationContext context, List<PeriodDate> periods,
            AmortizationState state) {
        LeaseElement element = context.element();
        BigDecimal repayable = element.principal()
                .setScale(element.rounding().scale(), element.rounding().mode())
                .subtract(residualOf(element));
        state.pmt(repayable.divide(BigDecimal.valueOf(periods.size()),
                element.rounding().scale(), element.rounding().mode()));
        state.deferredInterest(BigDecimal.ZERO);
    }

    @Override
    protected Allocation allocate(AllocationInput input, AmortizationState state) {
        if (!input.lastPeriod()) {
            state.deferredInterest(state.deferredInterest().add(input.accruedInterest()));
            return new Allocation(state.pmt(), BigDecimal.ZERO);
        }
        BigDecimal principal = input.remainingPrincipal().subtract(residualOf(input.element()));
        BigDecimal interestPaid = state.deferredInterest().add(input.accruedInterest());
        state.deferredInterest(BigDecimal.ZERO);
        return new Allocation(principal, interestPaid);
    }
}
