package com.fincalc.domain.support.validate;

import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.IssueLevel;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.Schedule;
import com.fincalc.domain.model.ValidationIssue;
import com.fincalc.domain.port.Validator;
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
     * The design document assigns one code to the whole rule family ({@code FCS-CALC-3004} 期次日期
     * 非单调), and the validator chain reports at most one finding per code and period, so every rule
     * a period breaks is named in that single finding.
     */
    private static final String CODE = "FCS-CALC-3004";

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
                issues.add(new ValidationIssue(IssueLevel.ERROR, CODE,
                        CODE + ": 第 " + current.periodNo() + " 期 " + String.join("；", causes),
                        current.periodNo()));
            }
            previous = current;
        }
        return issues;
    }
}
