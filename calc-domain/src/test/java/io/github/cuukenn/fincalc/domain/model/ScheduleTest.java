package io.github.cuukenn.fincalc.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleTest {

    @Test
    void schedule_is_immutable() {
        Schedule s = new Schedule(header(), new ArrayList<>(List.of(inst(1))),
                new ArrayList<>(List.of(new CashFlow(1, LocalDate.of(2024, 2, 1), new BigDecimal("100"),
                        CashFlowType.RENT, CashFlowDirection.INFLOW))),
                new ArrayList<>());
        assertThatThrownBy(() -> s.installments().add(inst(2)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.cashFlows().add(new CashFlow(2, LocalDate.of(2024, 3, 1),
                        new BigDecimal("100"), CashFlowType.RENT, CashFlowDirection.INFLOW)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.issues().add(
                        new ValidationIssue(IssueLevel.WARN, "FCS-CALC-3003", "IRR 不收敛", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void has_errors_detects_error_level_issue() {
        Schedule s = scheduleWithIssue(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3001", "本金不守恒", null));
        assertThat(s.hasErrors()).isTrue();
    }

    @Test
    void has_errors_ignores_info_and_warn_issues() {
        Schedule s = scheduleWithIssue(new ValidationIssue(IssueLevel.WARN, "FCS-CALC-3003", "IRR 不收敛", 12));
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void with_replaces_metrics_and_cash_flows_but_keeps_issues() {
        Schedule base = scheduleWithIssue(
                new ValidationIssue(IssueLevel.WARN, "FCS-CALC-3003", "IRR 不收敛", 12));
        FinancialMetrics enriched = FinancialMetrics.of(Collections.singletonMap("XIRR",
                new BigDecimal("0.0598")));
        CashFlow flow = new CashFlow(0, LocalDate.of(2024, 1, 1), new BigDecimal("1000000"),
                CashFlowType.PRINCIPAL, CashFlowDirection.OUTFLOW);

        Schedule enrichedSchedule = base.with(enriched, List.of(flow));

        assertThat(enrichedSchedule.header().metrics().find("XIRR")).isEqualByComparingTo("0.0598");
        assertThat(enrichedSchedule.cashFlows()).containsExactly(flow);
        assertThat(enrichedSchedule.installments()).containsExactly(base.installments().get(0));
        assertThat(enrichedSchedule.issues()).isEqualTo(base.issues());
        assertThat(base.header().metrics().codes()).isEmpty();
    }

    private ScheduleHeader header() {
        return new ScheduleHeader(FinancialMetrics.empty(), 12,
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1), "CLOSED_FORM", BigDecimal.ZERO);
    }

    private Installment inst(int periodNo) {
        return new Installment(periodNo,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1), 31,
                new BigDecimal("1000000.00"), new BigDecimal("81066.42"), new BigDecimal("5000.00"),
                new BigDecimal("5000.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("86066.42"), new BigDecimal("918933.58"),
                new BigDecimal("81066.42"), new BigDecimal("5000.00"),
                new BigDecimal("0.005000000000"), "R001");
    }

    private Schedule scheduleWithIssue(ValidationIssue issue) {
        return new Schedule(header(), List.of(inst(1)), Collections.emptyList(), List.of(issue));
    }
}
