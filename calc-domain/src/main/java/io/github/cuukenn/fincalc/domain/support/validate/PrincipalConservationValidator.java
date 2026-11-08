package io.github.cuukenn.fincalc.domain.support.validate;

import io.github.cuukenn.fincalc.common.money.MoneyTolerance;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.port.Validator;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Checks that the installments repay exactly the principal the contract says is repayable:
 * the full contract principal, or principal minus the ballooned residual under
 * {@link ResidualHandling#BALLOON_IN_RENT}.
 */
public final class PrincipalConservationValidator implements Validator {

    /**
     * A difference of one cent or more is reported; anything smaller is absorbed rounding. The value
     * is single-sourced in {@link MoneyTolerance} so every §3.4 check judges on the same line.
     */
    public static final BigDecimal TOLERANCE = MoneyTolerance.MONEY_COMPARE;

    @Override
    public String code() {
        return "PRINCIPAL_CONSERVATION";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<ValidationIssue> validate(Schedule schedule, LeaseElement element) {
        BigDecimal principalSum = MoneyUtils.ZERO;
        for (int index = 0; index < schedule.installments().size(); index++) {
            principalSum = principalSum.add(schedule.installments().get(index).principalDue());
        }
        BigDecimal expected = expectedRepayablePrincipal(element);
        if (principalSum.subtract(expected).abs().compareTo(TOLERANCE) < 0) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new ValidationIssue(IssueLevel.ERROR,
                CalcErrorCode.PRINCIPAL_CONSERVATION_BROKEN.code(),
                CalcErrorCode.PRINCIPAL_CONSERVATION_BROKEN.code() + ": 本金合计 "
                        + principalSum.toPlainString()
                        + " 与应还本金 " + expected.toPlainString() + " 不一致", null));
    }

    /**
     * Principal the schedule must repay under this element's residual policy, expressed at the
     * element's output scale: a generator scales the balance it amortizes, so comparing against the
     * raw contract principal would report a valid schedule whenever the rounding policy is coarser
     * than the input.
     */
    private static BigDecimal expectedRepayablePrincipal(LeaseElement element) {
        BigDecimal repayable = element.principal().subtract(element.outstandingResidual());
        return repayable.setScale(element.rounding().scale(), element.rounding().mode());
    }
}
