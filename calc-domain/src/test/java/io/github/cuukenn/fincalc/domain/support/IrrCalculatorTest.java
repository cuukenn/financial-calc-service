package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static io.github.cuukenn.fincalc.domain.model.CashFlowDirection.INFLOW;
import static io.github.cuukenn.fincalc.domain.model.CashFlowDirection.OUTFLOW;

import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.model.CashFlowType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IrrCalculatorTest {

    private static final LocalDate T0 = LocalDate.of(2024, 1, 1);

    @Test
    void npv_at_zero_is_inflow_minus_outflow() {
        assertThat(IrrCalculator.npv(flatLoanFlows(), BigDecimal.ZERO))
                .isEqualByComparingTo("32797.16");
    }

    @Test
    void npv_at_irr_is_zero() {
        List<CashFlow> flows = flatLoanFlows();
        BigDecimal irr = IrrCalculator.periodIrr(flows);

        assertThat(IrrCalculator.npv(flows, irr).abs()).isLessThan(new BigDecimal("0.01"));
    }

    @Test
    void period_irr_of_flat_loan_matches_periodic_rate() {
        assertThat(IrrCalculator.periodIrr(flatLoanFlows()))
                .isBetween(new BigDecimal("0.0049"), new BigDecimal("0.0051"));
    }

    @Test
    void annualization_supports_both_conventions() {
        // 1.005^12 − 1 = 0.0616778118644995…, half-up at 12 scales. The brief's constant
        // 0.061677811865 is one ulp off; the true arithmetic pins this.
        assertThat(IrrCalculator.annualize(new BigDecimal("0.005"), 12))
                .isEqualByComparingTo("0.061677811864");
        assertThat(IrrCalculator.simpleAnnualize(new BigDecimal("0.005"), 12))
                .isEqualByComparingTo("0.060000000000");
    }

    @Test
    void xirr_matches_excel_reference_across_leap_year() {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(flow(0, LocalDate.of(2024, 1, 1), "1000000", CashFlowType.PRINCIPAL, OUTFLOW));
        flows.add(flow(1, LocalDate.of(2025, 1, 1), "1100000", CashFlowType.RENT, INFLOW));

        // 2024 是闰年，间隔 366 天：r = 1.1^(365/366) − 1 ≈ 0.099710
        assertThat(IrrCalculator.xirr(flows))
                .isBetween(new BigDecimal("0.0990"), new BigDecimal("0.1000"));
    }

    @Test
    void xnpv_discounts_by_actual_days_over_365() {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(flow(0, LocalDate.of(2024, 1, 1), "1000000", CashFlowType.PRINCIPAL, OUTFLOW));
        flows.add(flow(1, LocalDate.of(2024, 7, 1), "1050000", CashFlowType.RENT, INFLOW));

        // 182 天：1 050 000 / (1.10)^(182/365) − 1 000 000 ≈ 1 266
        BigDecimal npv = IrrCalculator.xnpv(flows, new BigDecimal("0.10"));
        assertThat(npv).isBetween(new BigDecimal("1200"), new BigDecimal("1300"));
    }

    @Test
    void returns_null_when_no_sign_change() {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(flow(0, T0, "100", CashFlowType.RENT, INFLOW));
        flows.add(flow(1, T0.plusMonths(1), "100", CashFlowType.RENT, INFLOW));

        assertThat(IrrCalculator.periodIrr(flows)).isNull();
        assertThat(IrrCalculator.xirr(flows)).isNull();
        assertThat(IrrCalculator.hasSingleSignChange(flows)).isFalse();
    }

    @Test
    void has_single_sign_change_when_outflow_precedes_inflows() {
        assertThat(IrrCalculator.hasSingleSignChange(flatLoanFlows())).isTrue();
    }

    @Test
    void mirr_uses_reinvestment_and_finance_rates() {
        BigDecimal mirr = IrrCalculator.mirr(flatLoanFlows(),
                new BigDecimal("0.04"), new BigDecimal("0.08"), 12);

        // FV = Σ 86 066.43 × (1 + 0.04/12)^(12−k) at the §8 periodic-rate scale of 12
        //    = 1 051 943.745537485…, PV = 1 000 000, horizon 12 periods = 1 year
        // ⇒ (FV/PV)^1 − 1 = 0.05194374553748511…, half-up at 12 scales.
        assertThat(mirr).isEqualByComparingTo("0.051943745537");
        assertThat(mirr).isBetween(new BigDecimal("0.03"), new BigDecimal("0.09"));
    }

    @Test
    void npv_and_xnpv_reject_a_discount_rate_at_or_below_minus_one() {
        List<CashFlow> flows = flatLoanFlows();

        assertThatThrownBy(() -> IrrCalculator.npv(flows, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IrrCalculator.npv(flows, new BigDecimal("-1.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IrrCalculator.xnpv(flows, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empty_flow_list_is_rejected() {
        List<CashFlow> empty = new ArrayList<>();

        assertThatThrownBy(() -> IrrCalculator.npv(empty, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IrrCalculator.periodIrr(empty))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void annualize_and_mirr_reject_a_non_positive_periods_per_year() {
        assertThatThrownBy(() -> IrrCalculator.annualize(new BigDecimal("0.005"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IrrCalculator.simpleAnnualize(new BigDecimal("0.005"), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IrrCalculator.mirr(flatLoanFlows(),
                new BigDecimal("0.04"), new BigDecimal("0.08"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mirr_returns_null_when_one_side_of_the_flow_set_is_missing() {
        List<CashFlow> onlyInflows = new ArrayList<>();
        onlyInflows.add(flow(0, T0, "100", CashFlowType.RENT, INFLOW));
        onlyInflows.add(flow(1, T0.plusMonths(1), "100", CashFlowType.RENT, INFLOW));
        assertThat(IrrCalculator.mirr(onlyInflows,
                new BigDecimal("0.04"), new BigDecimal("0.08"), 12)).isNull();

        List<CashFlow> onlyOutflows = new ArrayList<>();
        onlyOutflows.add(flow(0, T0, "100", CashFlowType.PRINCIPAL, OUTFLOW));
        onlyOutflows.add(flow(1, T0.plusMonths(1), "10", CashFlowType.FEE, OUTFLOW));
        assertThat(IrrCalculator.mirr(onlyOutflows,
                new BigDecimal("0.04"), new BigDecimal("0.08"), 12)).isNull();
    }

    @Test
    void xnpv_uses_the_earliest_flow_date_whatever_the_insertion_order() {
        List<CashFlow> ordered = new ArrayList<>();
        ordered.add(flow(0, LocalDate.of(2024, 1, 1), "1000000", CashFlowType.PRINCIPAL, OUTFLOW));
        ordered.add(flow(1, LocalDate.of(2024, 7, 1), "1050000", CashFlowType.RENT, INFLOW));

        List<CashFlow> reversed = new ArrayList<>();
        reversed.add(flow(1, LocalDate.of(2024, 7, 1), "1050000", CashFlowType.RENT, INFLOW));
        reversed.add(flow(0, LocalDate.of(2024, 1, 1), "1000000", CashFlowType.PRINCIPAL, OUTFLOW));

        BigDecimal rate = new BigDecimal("0.10");
        assertThat(IrrCalculator.xnpv(reversed, rate))
                .isEqualByComparingTo(IrrCalculator.xnpv(ordered, rate));
    }

    @Test
    void a_negative_flow_amount_is_rejected_rather_than_double_negated() {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(flow(0, T0, "-1000000", CashFlowType.PRINCIPAL, OUTFLOW));

        assertThatThrownBy(() -> IrrCalculator.npv(flows, BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Golden annuity flows: 1 000 000 out at t0, twelve level rents of 86 066.43 in. */
    private static List<CashFlow> flatLoanFlows() {
        List<CashFlow> flows = new ArrayList<>();
        flows.add(flow(0, T0, "1000000.00", CashFlowType.PRINCIPAL, OUTFLOW));
        for (int period = 1; period <= 12; period++) {
            flows.add(flow(period, T0.plusMonths(period), "86066.43", CashFlowType.RENT, INFLOW));
        }
        return flows;
    }

    private static CashFlow flow(int sequence, LocalDate date, String amount, CashFlowType type,
            CashFlowDirection direction) {
        return new CashFlow(sequence, date, new BigDecimal(amount), type, direction);
    }
}
