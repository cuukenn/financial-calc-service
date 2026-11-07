package io.github.cuukenn.fincalc.domain.support.validate;

import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.port.Validator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AGENTS.md §3.4 校验三：逐期恒等式「期初余额 − 本期本金 = 期末余额」。Every installment must
 * carry the balances its own principal repayment implies, whatever algorithm produced the plan —
 * the safety net for schedules assembled outside the built-in generators.
 */
public final class PeriodIdentityValidator implements Validator {

    /** A difference of one cent or more is reported; anything smaller is absorbed rounding. */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    @Override
    public String code() {
        return "PERIOD_IDENTITY";
    }

    @Override
    public int order() {
        // Slots between TAIL_BALANCE (20) and DATE_MONOTONIC (30) so the chain runs in the
        // AGENTS.md code-number order 3001 → 3002 → 3008 → 3009 → 3004.
        return 21;
    }

    @Override
    public List<ValidationIssue> validate(Schedule schedule, LeaseElement element) {
        List<ValidationIssue> issues = new ArrayList<>();
        for (Installment installment : schedule.installments()) {
            BigDecimal expectedClosing = installment.openingBalance()
                    .subtract(installment.principalDue());
            if (expectedClosing.subtract(installment.closingBalance()).abs()
                    .compareTo(TOLERANCE) < 0) {
                continue;
            }
            issues.add(new ValidationIssue(IssueLevel.ERROR,
                    CalcErrorCode.PERIOD_IDENTITY_BROKEN.code(),
                    CalcErrorCode.PERIOD_IDENTITY_BROKEN.code() + ": 第 " + installment.periodNo()
                            + " 期 期初余额 " + installment.openingBalance().toPlainString()
                            + " 减 本期本金 " + installment.principalDue().toPlainString()
                            + " 应为 " + expectedClosing.toPlainString() + "，与期末余额 "
                            + installment.closingBalance().toPlainString() + " 不一致",
                    installment.periodNo()));
        }
        return Collections.unmodifiableList(issues);
    }
}
