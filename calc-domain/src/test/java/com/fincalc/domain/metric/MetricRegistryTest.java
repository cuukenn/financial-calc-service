package com.fincalc.domain.metric;

import static org.assertj.core.api.Assertions.assertThat;

import com.fincalc.common.time.SystemClockProvider;
import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.CalculationOptions;
import com.fincalc.domain.model.CashFlow;
import com.fincalc.domain.model.CashFlowDirection;
import com.fincalc.domain.model.GracePeriod;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PaymentFrequency;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.RateChangePolicy;
import com.fincalc.domain.model.RepaymentMethodId;
import com.fincalc.domain.model.ResidualHandling;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.model.Schedule;
import com.fincalc.domain.support.algo.EqualInstallmentGenerator;
import com.fincalc.domain.support.cashflow.StandardCashFlowBuilder;
import com.fincalc.domain.support.dateroll.NoRoll;
import com.fincalc.domain.support.daycount.Thirty360;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricRegistryTest {

    private static final LocalDate T0 = LocalDate.of(2024, 1, 1);

    @Test
    void computes_all_builtin_metrics() {
        MetricContext ctx = context(flatLoanSchedule());
        FinancialMetrics m = MetricRegistry.defaults().computeAll(ctx);

        assertThat(m.find(MetricCodes.TOTAL_PRINCIPAL)).isEqualByComparingTo("1000000.00");
        // 1 032 797.15, not the brief's 1 032 797.16: the final rent is tail-adjusted to 86 066.42.
        assertThat(m.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.15");
        assertThat(m.find(MetricCodes.RENTAL_RATE)).isEqualByComparingTo("1.032797");
        assertThat(m.find(MetricCodes.IRR_PERIOD)).isBetween(new BigDecimal("0.0049"),
                new BigDecimal("0.0051"));
        assertThat(m.find(MetricCodes.IRR_ANNUAL)).isNotNull();
        assertThat(m.find(MetricCodes.XIRR)).isNotNull();
        assertThat(m.find(MetricCodes.PAYBACK_PERIOD)).isBetween(new BigDecimal("0.9"),
                new BigDecimal("1.0"));
        assertThat(m.find(MetricCodes.DURATION_MACAULAY)).isGreaterThan(BigDecimal.ZERO);
        assertThat(m.find(MetricCodes.AVG_OUTSTANDING_BALANCE)).isBetween(
                new BigDecimal("400000"), new BigDecimal("600000"));
    }

    @Test
    void npv_at_irr_is_zero() {
        FinancialMetrics m = MetricRegistry.defaults().computeAll(context(flatLoanSchedule()));
        assertThat(m.find(MetricCodes.NPV).abs()).isLessThan(new BigDecimal("0.01"));
    }

    @Test
    void uncomputable_metric_is_absent_not_zero() {
        MetricContext ctx = context(scheduleWithSingleInflowOnly());
        FinancialMetrics m = MetricRegistry.defaults().computeAll(ctx);
        assertThat(m.find(MetricCodes.IRR_PERIOD)).isNull();
        assertThat(m.find(MetricCodes.PAYBACK_PERIOD)).isNull();
    }

    @Test
    void custom_metric_can_be_registered_without_touching_engine() {
        MetricRegistry reg = MetricRegistry.defaults()
                .register(new FinancialMetric() {
                    public String code() {
                        return "CUSTOM_X";
                    }

                    public BigDecimal compute(MetricContext c) {
                        return new BigDecimal("42");
                    }
                });
        assertThat(reg.computeAll(context(flatLoanSchedule())).find("CUSTOM_X"))
                .isEqualByComparingTo("42");
    }

    /** {@link MetricCodes#ALL} and {@link MetricRegistry#defaults()} describe the same 21 codes. */
    @Test
    void builtin_registry_covers_exactly_the_spec_metric_set() {
        Set<String> registered = new LinkedHashSet<>();
        for (FinancialMetric metric : MetricRegistry.defaults().metrics()) {
            registered.add(metric.code());
        }
        assertThat(MetricCodes.ALL).hasSize(21);
        assertThat(registered).hasSize(21);
        assertThat(registered).containsExactlyInAnyOrderElementsOf(MetricCodes.ALL);
    }

    @Test
    void computeAll_reports_the_codes_in_order_sequence() {
        MetricRegistry registry = MetricRegistry.defaults();
        List<String> registered = new ArrayList<>();
        for (FinancialMetric metric : registry.metrics()) {
            registered.add(metric.code());
        }

        List<String> reported = new ArrayList<>(
                registry.computeAll(context(flatLoanSchedule())).codes());

        assertThat(reported).containsExactlyElementsOf(registered);
    }

    /**
     * {@code order()} is a dependency contract: a yield consumer reads the yield out of the run, so
     * the solver must have been registered before it. Within a tier the tie is broken by
     * registration order, which is the part a refactor could silently break.
     */
    @Test
    void yield_dependent_metrics_are_registered_after_the_yield_they_consume() {
        List<String> sequence = new ArrayList<>();
        for (FinancialMetric metric : MetricRegistry.defaults().metrics()) {
            sequence.add(metric.code());
        }

        assertThat(sequence.indexOf(MetricCodes.IRR_PERIOD)).as("IRR_PERIOD first")
                .isGreaterThanOrEqualTo(0);
        assertThat(sequence.indexOf(MetricCodes.IRR_PERIOD))
                .isLessThan(sequence.indexOf(MetricCodes.IRR_ANNUAL));
        assertThat(sequence.indexOf(MetricCodes.IRR_PERIOD))
                .isLessThan(sequence.indexOf(MetricCodes.IRR_ANNUAL_SIMPLE));
        assertThat(sequence.indexOf(MetricCodes.XIRR))
                .isLessThan(sequence.indexOf(MetricCodes.TRUE_ANNUAL_RATE));
        assertThat(sequence.indexOf(MetricCodes.IRR_PERIOD))
                .isLessThan(sequence.indexOf(MetricCodes.NPV));
        assertThat(sequence.indexOf(MetricCodes.XIRR))
                .isLessThan(sequence.indexOf(MetricCodes.XNPV));
        assertThat(sequence.indexOf(MetricCodes.IRR_PERIOD))
                .isLessThan(sequence.indexOf(MetricCodes.DISCOUNTED_PAYBACK));
        assertThat(sequence.indexOf(MetricCodes.IRR_PERIOD))
                .isLessThan(sequence.indexOf(MetricCodes.DURATION_MACAULAY));
    }

    /**
     * Deviation from the brief's「单个指标异常只记 WARN 并跳过」: this build has no logger, so a
     * failure is recorded as the present-but-null marker {@link FinancialMetrics} documents for a
     * later engine to turn into a {@code ValidationIssue}, and the run continues.
     */
    @Test
    void a_metric_that_throws_is_recorded_absent_and_does_not_stop_the_run() {
        MetricRegistry registry = MetricRegistry.empty()
                .register(new FinancialMetric() {
                    public String code() {
                        return "BROKEN";
                    }

                    public BigDecimal compute(MetricContext context) {
                        throw new IllegalStateException("simulated metric failure");
                    }
                })
                .register(new FinancialMetric() {
                    public String code() {
                        return "STILL_COMPUTED";
                    }

                    public BigDecimal compute(MetricContext context) {
                        return new BigDecimal("7.50");
                    }
                });

        FinancialMetrics metrics = registry.computeAll(context(flatLoanSchedule()));

        assertThat(metrics.codes()).containsExactly("BROKEN", "STILL_COMPUTED");
        assertThat(metrics.find("BROKEN")).isNull();
        assertThat(metrics.find("STILL_COMPUTED")).isEqualByComparingTo("7.50");
    }

    /** A context with a schedule but no yield to read: the yield tier is absent, not zero. */
    @Test
    void a_flow_set_with_no_sign_change_leaves_every_yield_consumer_absent() {
        FinancialMetrics metrics =
                MetricRegistry.defaults().computeAll(context(scheduleWithSingleInflowOnly()));

        assertThat(metrics.find(MetricCodes.IRR_ANNUAL)).isNull();
        assertThat(metrics.find(MetricCodes.IRR_ANNUAL_SIMPLE)).isNull();
        assertThat(metrics.find(MetricCodes.XIRR)).isNull();
        assertThat(metrics.find(MetricCodes.TRUE_ANNUAL_RATE)).isNull();
        assertThat(metrics.find(MetricCodes.MIRR)).isNull();
        assertThat(metrics.find(MetricCodes.NPV)).isNull();
        assertThat(metrics.find(MetricCodes.XNPV)).isNull();
        assertThat(metrics.find(MetricCodes.DISCOUNTED_PAYBACK)).isNull();
        assertThat(metrics.find(MetricCodes.DURATION_MACAULAY)).isNull();
        assertThat(metrics.find(MetricCodes.DURATION_MODIFIED)).isNull();
        assertThat(metrics.find(MetricCodes.CONVEXITY)).isNull();
        // The schedule-derived codes are still there: only the yield-driven block is absent.
        assertThat(metrics.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.15");
        assertThat(metrics.find(MetricCodes.AVG_OUTSTANDING_BALANCE)).isNotNull();
    }

    /**
     * A configured discount rate makes the present-value and sensitivity block computable even when
     * the flow set yields nothing, while the metrics that need an actual outlay stay absent.
     */
    @Test
    void a_configured_discount_rate_replaces_the_missing_yield() {
        Schedule schedule = scheduleWithSingleInflowOnly();
        MetricContext context = new MetricContext(schedule.cashFlows(), schedule, element(), 12,
                new BigDecimal("0.005"), new BigDecimal("0.04"), new BigDecimal("0.08"));

        FinancialMetrics metrics = MetricRegistry.defaults().computeAll(context);

        assertThat(metrics.find(MetricCodes.IRR_PERIOD)).isNull();
        assertThat(metrics.find(MetricCodes.NPV)).isNotNull();
        assertThat(metrics.find(MetricCodes.XNPV)).isNotNull();
        assertThat(metrics.find(MetricCodes.DURATION_MACAULAY)).isNotNull();
        assertThat(metrics.find(MetricCodes.CONVEXITY)).isNotNull();
        assertThat(metrics.find(MetricCodes.PAYBACK_PERIOD)).isNull();
        assertThat(metrics.find(MetricCodes.DISCOUNTED_PAYBACK)).isNull();
        assertThat(metrics.find(MetricCodes.MIRR)).isNull();
    }

    /**
     * The full golden run, pinned to the numbers the real generator produces; they double as the
     * arithmetic record for the task report.
     *
     * <p>The rent total is 1 032 797.15, not the 1 032 797.16 the task brief quoted: eleven rents of
     * 86 066.43 plus a tail-adjusted final rent of 86 066.42 (the last period absorbs the rounding
     * difference and closes the balance to exactly 0.00). The pinned value is what the schedule
     * actually says.
     */
    @Test
    void golden_run_produces_the_pinned_metric_values() {
        FinancialMetrics metrics = MetricRegistry.defaults().computeAll(context(flatLoanSchedule()));

        assertThat(metrics.find(MetricCodes.TOTAL_PRINCIPAL)).isEqualByComparingTo("1000000.00");
        assertThat(metrics.find(MetricCodes.TOTAL_INTEREST)).isEqualByComparingTo("32797.15");
        assertThat(metrics.find(MetricCodes.TOTAL_FEE)).isEqualByComparingTo("0.00");
        assertThat(metrics.find(MetricCodes.TOTAL_TAX)).isEqualByComparingTo("0.00");
        assertThat(metrics.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.15");
        assertThat(metrics.find(MetricCodes.RESIDUAL_VALUE)).isEqualByComparingTo("0.00");
        assertThat(metrics.find(MetricCodes.RENTAL_RATE)).isEqualByComparingTo("1.032797");
        assertThat(metrics.find(MetricCodes.AVG_OUTSTANDING_BALANCE))
                .isEqualByComparingTo("546619.27");
        assertThat(metrics.find(MetricCodes.IRR_PERIOD)).isEqualByComparingTo("0.0049999991");
        // The annual pair is derived from the published periodic root, not from the solver's raw
        // answer: (1 + 0.0049999991)^12 − 1 and 0.0049999991 × 12.
        assertThat(metrics.find(MetricCodes.IRR_ANNUAL)).isEqualByComparingTo("0.061677800455");
        assertThat(metrics.find(MetricCodes.IRR_ANNUAL_SIMPLE)).isEqualByComparingTo("0.059999989200");
        // No reinvest / finance rates configured: both default to the 0.06 nominal rate.
        assertThat(metrics.find(MetricCodes.MIRR)).isEqualByComparingTo("0.061677805478");
        assertThat(metrics.find(MetricCodes.XIRR)).isEqualByComparingTo("0.0616393559");
        assertThat(metrics.find(MetricCodes.TRUE_ANNUAL_RATE))
                .isEqualByComparingTo(metrics.find(MetricCodes.XIRR));
        // 1 000 000 out, eleven rents of 86 066.43 and a final 86 066.42 in: 11 + 53 269.27 /
        // 86 066.42 = 11.6189320992 periods, / 12 = 0.9682443416 years.
        assertThat(metrics.find(MetricCodes.PAYBACK_PERIOD)).isEqualByComparingTo("0.9682443416");
        // At the yield the discounted sum closes at the last flow, so the dynamic payback is the
        // horizon; the 3e-10 excess is the scale-10 rounding of IRR_PERIOD, not a defect.
        assertThat(metrics.find(MetricCodes.DISCOUNTED_PAYBACK)).isEqualByComparingTo("1.0000000003");
        assertThat(metrics.find(MetricCodes.NPV)).isEqualByComparingTo("0.00");
        assertThat(metrics.find(MetricCodes.XNPV)).isEqualByComparingTo("0.00");
        assertThat(metrics.find(MetricCodes.DURATION_MACAULAY)).isEqualByComparingTo("0.2683570275");
        assertThat(metrics.find(MetricCodes.DURATION_MODIFIED)).isEqualByComparingTo("0.2670219182");
        assertThat(metrics.find(MetricCodes.CONVEXITY)).isEqualByComparingTo("0.2057017672");
    }

    /** The §9.5 scales, pinned per metric rather than trusted to a later rounding step. */
    @Test
    void every_metric_publishes_its_own_scale() {
        FinancialMetrics metrics = MetricRegistry.defaults().computeAll(context(flatLoanSchedule()));

        for (String code : MetricCodes.ALL) {
            assertThat(metrics.find(code)).as(code).isNotNull();
        }
        assertThat(metrics.find(MetricCodes.TOTAL_PRINCIPAL).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.TOTAL_INTEREST).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.TOTAL_FEE).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.TOTAL_TAX).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.TOTAL_RENT).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.RESIDUAL_VALUE).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.AVG_OUTSTANDING_BALANCE).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.NPV).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.XNPV).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.RENTAL_RATE).scale()).isEqualTo(6);
        assertThat(metrics.find(MetricCodes.IRR_PERIOD).scale()).isEqualTo(10);
        assertThat(metrics.find(MetricCodes.XIRR).scale()).isEqualTo(10);
        assertThat(metrics.find(MetricCodes.TRUE_ANNUAL_RATE).scale()).isEqualTo(10);
        assertThat(metrics.find(MetricCodes.PAYBACK_PERIOD).scale()).isEqualTo(10);
        assertThat(metrics.find(MetricCodes.DISCOUNTED_PAYBACK).scale()).isEqualTo(10);
        assertThat(metrics.find(MetricCodes.DURATION_MACAULAY).scale()).isEqualTo(10);
        assertThat(metrics.find(MetricCodes.DURATION_MODIFIED).scale()).isEqualTo(10);
        assertThat(metrics.find(MetricCodes.CONVEXITY).scale()).isEqualTo(10);
        assertThat(metrics.find(MetricCodes.IRR_ANNUAL).scale()).isEqualTo(12);
        assertThat(metrics.find(MetricCodes.IRR_ANNUAL_SIMPLE).scale()).isEqualTo(12);
        assertThat(metrics.find(MetricCodes.MIRR).scale()).isEqualTo(12);
    }

    /**
     * A five-year monthly lease is the check that the year conversions are right: the static payback
     * must land in years (about 4.3), the dynamic one at the yield must land on the five-year horizon,
     * and the duration must be a fraction of the term rather than the 60-period count.
     */
    @Test
    void a_five_year_schedule_keeps_the_period_and_year_coordinates_straight() {
        LeaseElement element = element().toBuilder().periods(60).build();
        Schedule schedule = new EqualInstallmentGenerator().generate(new CalculationContext(element,
                null, new Thirty360(), new NoRoll(), SystemClockProvider.INSTANCE,
                CalculationOptions.defaults()));
        FinancialMetrics metrics = MetricRegistry.defaults().computeAll(
                new MetricContext(flowsOf(schedule), schedule, element, 12, null, null, null));

        assertThat(schedule.installments()).hasSize(60);
        assertThat(metrics.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1159968.09");
        assertThat(metrics.find(MetricCodes.PAYBACK_PERIOD)).isBetween(new BigDecimal("4.30"),
                new BigDecimal("4.32"));
        assertThat(metrics.find(MetricCodes.DISCOUNTED_PAYBACK)).isBetween(new BigDecimal("4.99"),
                new BigDecimal("5.01"));
        assertThat(metrics.find(MetricCodes.DURATION_MACAULAY)).isBetween(new BigDecimal("1.20"),
                new BigDecimal("1.22"));
        assertThat(metrics.find(MetricCodes.DURATION_MODIFIED)).isBetween(new BigDecimal("1.20"),
                new BigDecimal("1.21"));
        assertThat(metrics.find(MetricCodes.CONVEXITY)).isBetween(new BigDecimal("4.01"),
                new BigDecimal("4.03"));
    }

    private static Schedule flatLoanSchedule() {
        return new EqualInstallmentGenerator().generate(new CalculationContext(element(), null,
                new Thirty360(), new NoRoll(), SystemClockProvider.INSTANCE,
                CalculationOptions.defaults()));
    }

    /**
     * The same schedule seen as a flow set with nothing ever paid out: no outflow to recover, no
     * sign change to bracket a root, so the whole yield block is not computable.
     */
    private static Schedule scheduleWithSingleInflowOnly() {
        Schedule flat = flatLoanSchedule();
        List<CashFlow> inflows = new ArrayList<>();
        for (CashFlow flow : flowsOf(flat)) {
            inflows.add(new CashFlow(flow.sequence(), flow.date(), flow.amount(), flow.type(),
                    CashFlowDirection.INFLOW));
        }
        return flat.with(FinancialMetrics.empty(), inflows);
    }

    /**
     * A run reads the flows its schedule already carries — the enriched form
     * {@link com.fincalc.domain.model.Schedule#with} produces — and builds the §9.4 event table when
     * the schedule is the raw generator output.
     */
    private static MetricContext context(Schedule schedule) {
        List<CashFlow> flows = schedule.cashFlows().isEmpty() ? flowsOf(schedule)
                : schedule.cashFlows();
        return new MetricContext(flows, schedule, element(), 12, null, null, null);
    }

    private static List<CashFlow> flowsOf(Schedule schedule) {
        return new StandardCashFlowBuilder().build(schedule, element(),
                CalculationOptions.defaults());
    }

    private static LeaseElement element() {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000"))
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(T0)
                .firstRentDate(LocalDate.of(2024, 2, 1))
                .periods(12)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualValue(BigDecimal.ZERO)
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(BigDecimal.ZERO)
                .vatRate(new BigDecimal("0.06"))
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
