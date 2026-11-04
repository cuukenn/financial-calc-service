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
            if (!current.startDate().isBefore(current.endDate())) {
                issues.add(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3004",
                        "FCS-CALC-3004: 第 " + current.periodNo() + " 期起始日 "
                                + current.startDate() + " 不早于结束日 " + current.endDate(),
                        current.periodNo()));
            }
            if (previous != null) {
                if (current.periodNo() <= previous.periodNo()) {
                    issues.add(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3004",
                            "FCS-CALC-3004: 期次编号 " + current.periodNo()
                                    + " 未在后一期 " + previous.periodNo() + " 之后递增",
                            current.periodNo()));
                }
                if (!current.startDate().isEqual(previous.endDate())) {
                    issues.add(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3004",
                            "FCS-CALC-3004: 第 " + current.periodNo() + " 期起始日 "
                                    + current.startDate() + " 与第 " + previous.periodNo()
                                    + " 期结束日 " + previous.endDate() + " 不连续",
                            current.periodNo()));
                }
            }
            previous = current;
        }
        return issues;
    }
}
