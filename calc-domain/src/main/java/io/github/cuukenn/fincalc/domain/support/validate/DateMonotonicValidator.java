package io.github.cuukenn.fincalc.domain.support.validate;

import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.port.Validator;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the period windows of a finished schedule are usable: each window non-empty, each
 * window starting exactly where the previous one ended, and period numbers strictly ascending.
 *
 * <p>{@code PeriodBuilder} already refuses to build a non-monotonic sequence; this validator is the
 * schedule-level safety net for schedules assembled elsewhere (a rule-driven generator, a restored
 * snapshot, an imported plan).
 */
public final class DateMonotonicValidator implements Validator {

    /**
     * The design document assigns one code to the whole rule family (期次日期非单调), carried here as
     * {@link CalcErrorCode#PERIOD_DATE_ORDER_INVALID}. The validator chain reports at most one finding
     * per code and period, so every rule a period breaks is named in that single finding.
     */
    @Override
    public String code() {
        return "DATE_MONOTONIC";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public List<ValidationIssue> validate(Schedule schedule, LeaseElement element) {
        String code = CalcErrorCode.PERIOD_DATE_ORDER_INVALID.code();
        List<ValidationIssue> issues = new ArrayList<>();
        List<Installment> installments = schedule.installments();
        Installment previous = null;
        for (Installment current : installments) {
            List<String> causes = new ArrayList<>(3);
            if (!current.startDate().isBefore(current.endDate())) {
                causes.add("起始日 " + current.startDate() + " 不早于结束日 " + current.endDate());
            }
            if (previous != null) {
                if (current.periodNo() <= previous.periodNo()) {
                    causes.add("期次编号 " + current.periodNo() + " 未在后一期 "
                            + previous.periodNo() + " 之后递增");
                }
                if (!current.startDate().isEqual(previous.endDate())) {
                    causes.add("起始日 " + current.startDate() + " 与第 " + previous.periodNo()
                            + " 期结束日 " + previous.endDate() + " 不连续");
                }
            }
            if (!causes.isEmpty()) {
                issues.add(new ValidationIssue(IssueLevel.ERROR, code,
                        code + ": 第 " + current.periodNo() + " 期 " + String.join("；", causes),
                        current.periodNo()));
            }
            previous = current;
        }
        return issues;
    }
}
