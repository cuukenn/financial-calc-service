package com.fincalc.domain.support.algo;

import java.math.BigDecimal;

import com.fincalc.common.error.BusinessException;
import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.DomainErrorCode;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.RepaymentMethodId;
import com.fincalc.domain.support.AbstractAmortizationGenerator;

/**
 * Interest-first schedule: every period pays interest only and the whole outstanding principal is
 * repaid with the final rent.
 *
 * <p>Like the two amortizing methods this generator only models rents that fall due at the end of
 * their period: an interest-only schedule paid in advance would charge a full period of interest on
 * principal that the payment date already returns, so {@code IN_ADVANCE} is rejected rather than
 * silently mispriced.
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
        return context.element().method() == RepaymentMethodId.INTEREST_FIRST
                && context.element().timing() == PaymentTiming.IN_ARREARS;
    }

    @Override
    protected void validate(LeaseElement element) {
        if (element.timing() != PaymentTiming.IN_ARREARS) {
            throw new BusinessException(DomainErrorCode.UNSUPPORTED_PAYMENT_TIMING, element.timing());
        }
    }

    @Override
    protected Allocation allocate(AllocationInput input, AmortizationState state) {
        BigDecimal principal = input.lastPeriod()
                ? input.remainingPrincipal().subtract(input.element().outstandingResidual())
                : BigDecimal.ZERO;
        return new Allocation(principal, input.accruedInterest());
    }
}
