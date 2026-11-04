package com.fincalc.domain.support.validate;

import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.model.IssueLevel;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.ResidualHandling;
import com.fincalc.domain.model.Schedule;
import com.fincalc.domain.model.ValidationIssue;
import com.fincalc.domain.port.Validator;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Checks that the installments repay exactly the principal the contract says is repayable:
 * the full contract principal, or principal minus the ballooned residual under
 * {@link ResidualHandling#BALLOON_IN_RENT}.
 */
public final class PrincipalConservationValidator implements Validator {

    /** A difference of one cent or more is reported; anything smaller is absorbed rounding. */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.01");

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
        return Collections.singletonList(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3001",
                "FCS-CALC-3001: 本金合计 " + principalSum.toPlainString()
                        + " 与应还本金 " + expected.toPlainString() + " 不一致", null));
    }

    /** Principal the schedule must repay under this element's residual policy. */
    private static BigDecimal expectedRepayablePrincipal(LeaseElement element) {
        return element.principal().subtract(element.outstandingResidual());
    }
}
