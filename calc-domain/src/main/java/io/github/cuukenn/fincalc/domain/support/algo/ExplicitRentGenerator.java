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
 * Explicit-rent schedule (设计文档 §8.1 {@code 04_现金流} 模式, R69): every period's rent is
 * taken verbatim from {@link LeaseElement#explicitRents()} — the rents are given, not derived —
 * and the engine splits each one into interest and principal.
 *
 * <p>Per period: interest accrues on the opening balance at the day-count-converted period rate
 * (the base template's hooks, so rule-resolved segmented rates apply unchanged), principal is
 * {@code rent - interest} and the closing balance is whatever the rents produce. There is
 * deliberately <b>no last-period clamp</b>: the rents are an input, so a sequence that does not
 * amortize the balance to {@code outstandingResidual()} keeps its schedule and the conservation
 * validators flag it ({@code FCS-CALC-3001}/{@code FCS-CALC-3002}, {@code hasErrors() == true})
 * — never absorbed, never adjusted. A rent that cannot even cover the period's accrued interest
 * would need negative principal and fails loud through the R17 backstop
 * ({@code FCS-DOM-2003}).
 *
 * <p>Like the other built-in methods this generator models rents due at the end of their period;
 * {@code IN_ADVANCE} is rejected rather than silently mispriced. The generator additionally
 * re-checks the rent sequence against the built periods in {@link #prepare} (backstop for
 * elements that did not come through the compiler/validated builder —
 * {@link io.github.cuukenn.fincalc.domain.model.LeaseElement}'s own validation already fails
 * loud with the same code, {@code FCS-DOM-1008}, for builder-built elements, so no new error
 * code is introduced for it).
 */
public final class ExplicitRentGenerator extends AbstractAmortizationGenerator {

    @Override
    public String code() {
        return "EXPLICIT_RENT";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public boolean supports(CalculationContext context) {
        return context.element().method() == RepaymentMethodId.EXPLICIT_RENT
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
        List<BigDecimal> rents = context.element().explicitRents();
        if (rents.isEmpty() || rents.size() != periods.size()) {
            throw new BusinessException(DomainErrorCode.EXPLICIT_RENTS_INVALID, rents.size()
                    + " <> " + periods.size());
        }
    }

    @Override
    protected Allocation allocate(AllocationInput input, AmortizationState state) {
        BigDecimal rent = input.element().explicitRents().get(input.periodNo() - 1);
        BigDecimal principal = money(rent.subtract(input.accruedInterest()), input.element());
        if (principal.signum() < 0) {
            // R17 backstop: a rent below the period's accrued interest cannot be split.
            throw new BusinessException(DomainErrorCode.NEGATIVE_PRINCIPAL_DUE,
                    input.periodNo(), principal.toPlainString());
        }
        // R69: no last-period clamp — the rent sequence alone decides the closing balance.
        return new Allocation(principal, input.accruedInterest());
    }

    private static BigDecimal money(BigDecimal value, LeaseElement element) {
        return value.setScale(element.rounding().scale(), element.rounding().mode());
    }
}
