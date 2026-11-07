package io.github.cuukenn.fincalc.domain.support.algo;

import java.math.BigDecimal;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.DomainErrorCode;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.support.AbstractAmortizationGenerator;

/**
 * Bullet-loan schedule (到期一次还本付息): nothing is paid during the term and the final rent settles
 * both the outstanding principal and every period of accrued interest in one payment.
 *
 * <p>Interest accrues on the outstanding principal only — deferred interest does not compound, and
 * it never enters the balance, so the balance line stays flat at the principal until maturity.
 *
 * <p>Like the other built-in methods this generator models rents due at the end of their period; an
 * {@code IN_ADVANCE} schedule would charge interest on principal the payment date already returns,
 * so it is rejected rather than silently mispriced.
 *
 * <p>Under a balloon residual the final period repays {@code principal - residual} and the residual
 * stays outstanding at maturity, matching the conservation contract of
 * {@link AbstractAmortizationGenerator}.
 */
public final class BulletLoanGenerator extends AbstractAmortizationGenerator {

    @Override
    public String code() {
        return "BULLET_LOAN";
    }

    @Override
    public int order() {
        return 45;
    }

    @Override
    public boolean supports(CalculationContext context) {
        return context.element().method() == RepaymentMethodId.BULLET_LOAN
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
        if (!input.lastPeriod()) {
            state.deferredInterest(state.deferredInterest().add(input.accruedInterest()));
            return new Allocation(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal principal = input.remainingPrincipal().subtract(input.element().outstandingResidual());
        BigDecimal interestPaid = state.deferredInterest().add(input.accruedInterest());
        state.deferredInterest(BigDecimal.ZERO);
        return new Allocation(principal, interestPaid);
    }
}
