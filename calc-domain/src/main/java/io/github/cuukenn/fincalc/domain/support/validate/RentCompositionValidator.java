package io.github.cuukenn.fincalc.domain.support.validate;

import io.github.cuukenn.fincalc.common.money.MoneyTolerance;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.port.Validator;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * AGENTS.md §3.4 校验四：Σ租金 = Σ本金 + Σ利息（实付 {@code interestPaid}）+ Σ费用 + Σ税
 * （税为 {@code null} 计 0）。The rent of every period must decompose exactly into the amounts
 * the schedule itself reports as its components.
 */
public final class RentCompositionValidator implements Validator {

    /**
     * A difference of one cent or more is reported; anything smaller is absorbed rounding. The value
     * is single-sourced in {@link MoneyTolerance} so every §3.4 check judges on the same line.
     */
    public static final BigDecimal TOLERANCE = MoneyTolerance.MONEY_COMPARE;

    @Override
    public String code() {
        return "RENT_COMPOSITION";
    }

    @Override
    public int order() {
        // Slots between TAIL_BALANCE (20) and DATE_MONOTONIC (30) so the chain runs in the
        // AGENTS.md §3.4 code-number order:
        // CalcErrorCode.PRINCIPAL_CONSERVATION_BROKEN → TAIL_BALANCE_MISMATCH →
        // PERIOD_IDENTITY_BROKEN → RENT_COMPOSITION_BROKEN → PERIOD_DATE_ORDER_INVALID.
        return 22;
    }

    @Override
    public List<ValidationIssue> validate(Schedule schedule, LeaseElement element) {
        BigDecimal rentSum = MoneyUtils.ZERO;
        BigDecimal principalSum = MoneyUtils.ZERO;
        BigDecimal interestSum = MoneyUtils.ZERO;
        BigDecimal feeSum = MoneyUtils.ZERO;
        BigDecimal taxSum = MoneyUtils.ZERO;
        for (Installment installment : schedule.installments()) {
            rentSum = rentSum.add(installment.rentDue());
            principalSum = principalSum.add(installment.principalDue());
            interestSum = interestSum.add(installment.interestPaid());
            feeSum = feeSum.add(installment.fee() == null ? MoneyUtils.ZERO : installment.fee());
            taxSum = taxSum.add(installment.tax() == null ? MoneyUtils.ZERO : installment.tax());
        }
        BigDecimal componentSum = principalSum.add(interestSum).add(feeSum).add(taxSum);
        if (rentSum.subtract(componentSum).abs().compareTo(TOLERANCE) < 0) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new ValidationIssue(IssueLevel.ERROR,
                CalcErrorCode.RENT_COMPOSITION_BROKEN.code(),
                CalcErrorCode.RENT_COMPOSITION_BROKEN.code() + ": Σ租金 "
                        + rentSum.toPlainString() + " 与 Σ本金 " + principalSum.toPlainString()
                        + " + Σ利息 " + interestSum.toPlainString() + " + Σ费用 "
                        + feeSum.toPlainString() + " + Σ税 " + taxSum.toPlainString()
                        + " 不一致", null));
    }
}
