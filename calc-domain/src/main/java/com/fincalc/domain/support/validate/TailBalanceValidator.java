package com.fincalc.domain.support.validate;

import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.IssueLevel;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.Schedule;
import com.fincalc.domain.model.ValidationIssue;
import com.fincalc.domain.port.Validator;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Checks that the last installment leaves exactly the balance the residual policy asks for:
 * zero, or the residual when it was ballooned into the rent.
 */
public final class TailBalanceValidator implements Validator {

    /** A difference of one cent or more is reported; anything smaller is absorbed rounding. */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    @Override
    public String code() {
        return "TAIL_BALANCE";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<ValidationIssue> validate(Schedule schedule, LeaseElement element) {
        List<Installment> installments = schedule.installments();
        if (installments.isEmpty()) {
            return Collections.emptyList();
        }
        Installment last = installments.get(installments.size() - 1);
        BigDecimal expected = expectedClosingBalance(element);
        if (last.closingBalance().subtract(expected).abs().compareTo(TOLERANCE) < 0) {
            return Collections.emptyList();
        }
        return Collections.singletonList(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3002",
                "FCS-CALC-3002: 末期余额 " + last.closingBalance().toPlainString()
                        + " 与预期 " + expected.toPlainString() + " 不一致", last.periodNo()));
    }

    /** Balance the schedule must end with, given the residual policy. */
    private static BigDecimal expectedClosingBalance(LeaseElement element) {
        return element.outstandingResidual();
    }
}
