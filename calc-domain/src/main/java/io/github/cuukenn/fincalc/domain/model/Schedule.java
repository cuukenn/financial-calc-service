package io.github.cuukenn.fincalc.domain.model;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of one amortization calculation: header summary, per-period installments,
 * the IRR cash flow view and validation findings.
 *
 * <p>A generator produces a schedule without metrics and cash flows; the engine enriches it
 * through {@link #with(FinancialMetrics, List)} so metric computation stays out of the
 * generator's responsibility.
 */
public final class Schedule {

    private final ScheduleHeader header;
    private final List<Installment> installments;
    private final List<CashFlow> cashFlows;
    private final List<ValidationIssue> issues;

    public Schedule(ScheduleHeader header, List<Installment> installments,
            List<CashFlow> cashFlows, List<ValidationIssue> issues) {
        this.header = Objects.requireNonNull(header, "header");
        this.installments = Collections.unmodifiableList(
                installments == null ? Collections.emptyList() : new ArrayList<>(installments));
        this.cashFlows = Collections.unmodifiableList(
                cashFlows == null ? Collections.emptyList() : new ArrayList<>(cashFlows));
        this.issues = Collections.unmodifiableList(
                issues == null ? Collections.emptyList() : new ArrayList<>(issues));
    }

    public ScheduleHeader header() {
        return header;
    }

    public List<Installment> installments() {
        return installments;
    }

    public List<CashFlow> cashFlows() {
        return cashFlows;
    }

    public List<ValidationIssue> issues() {
        return issues;
    }

    /** True when any {@link ValidationIssue} has level {@link IssueLevel#ERROR}. */
    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.level() == IssueLevel.ERROR);
    }

    /**
     * Derives an enriched schedule: replaces header metrics and cash flows, keeps installments
     * and issues. Use this after cash flows and metrics have been computed for a raw schedule.
     */
    public Schedule with(FinancialMetrics metrics, List<CashFlow> cashFlows) {
        ScheduleHeader enrichedHeader = new ScheduleHeader(
                metrics == null ? FinancialMetrics.empty() : metrics,
                header.periods(), header.firstDate(), header.lastDate(),
                header.solver(), header.roundingDifference());
        return new Schedule(enrichedHeader, installments, cashFlows, issues);
    }
}
