package io.github.cuukenn.fincalc.domain.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.model.CashFlowType;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ScheduleHeader;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import io.github.cuukenn.fincalc.domain.support.algo.EqualInstallmentGenerator;
import io.github.cuukenn.fincalc.domain.support.cashflow.StandardCashFlowBuilder;
import io.github.cuukenn.fincalc.domain.support.dateroll.NoRoll;
import io.github.cuukenn.fincalc.domain.support.daycount.Thirty360;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * R103: the metric layer's perspective (lessor vs lessee / borrower), the {@code BORROWER_*} family
 * and the guarantee that a run which never names a perspective is the run v1 produced.
 *
 * <p><b>The mirror and the root.</b> {@link MetricSupport#flowsFor(MetricContext)} keeps every
 * amount positive and flips only {@link CashFlowDirection}, so the LESSEE flow set is the exact
 * negation of the LESSOR one in signed terms. A global sign flip negates the NPV function without
 * moving its zeros, so the borrower yield is the <em>same number</em> as the lessor yield — the
 * mirror lives in the direction of the money (and in the payback walks), not in the rate. The
 * pinned numbers below are therefore the golden table's own root, re-derived through the flipped
 * flow set by a second, independent solve.
 *
 * <p><b>Golden fixture</b> ({@link MetricRegistryTest#golden_run_produces_the_pinned_metric_values}
 * for the LESSOR side): 1 000 000 principal at 6 % nominal, THIRTY_360, 12 monthly rents in
 * arrears. Rents 11 × 86 066.43 + 86 066.42 = 1 032 797.15; no fees, no tax, no residual, no
 * deposit.
 */
class MetricPerspectiveTest {

    private static final LocalDate T0 = LocalDate.of(2024, 1, 1);

    /** The R103 borrower family; a LESSOR run must carry all four present-but-null. */
    private static final Set<String> BORROWER_CODES = new LinkedHashSet<>(Arrays.asList(
            MetricCodes.BORROWER_TOTAL_REPAYMENT, MetricCodes.BORROWER_IRR_PERIOD,
            MetricCodes.BORROWER_IRR_ANNUAL, MetricCodes.BORROWER_IRR_ANNUAL_SIMPLE));

    // ---------------------------------------------------------------- LESSOR is unchanged

    /**
     * A run that never names a perspective is the v1 run: the 21 §9.5 codes carry the golden
     * values (pinned here as the regression anchor for the flip) and the three {@code BORROWER_*}
     * codes are present-but-null, the registry's existing posture for a metric that cannot compute.
     */
    @Test
    void a_lessor_run_keeps_the_21_golden_codes_and_leaves_the_borrower_family_null() {
        MetricContext context = context(flatLoanSchedule(), MetricPerspective.LESSOR);
        FinancialMetrics metrics = MetricRegistry.defaults().computeAll(context);

        assertThat(context.perspective()).isEqualTo(MetricPerspective.LESSOR);

        assertThat(metrics.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.15");
        assertThat(metrics.find(MetricCodes.IRR_PERIOD)).isEqualByComparingTo("0.0049999991");
        assertThat(metrics.find(MetricCodes.IRR_ANNUAL)).isEqualByComparingTo("0.061677800455");
        assertThat(metrics.find(MetricCodes.PAYBACK_PERIOD)).isEqualByComparingTo("0.9682443416");

        for (String code : BORROWER_CODES) {
            assertThat(metrics.codes()).as("%s must be present", code).contains(code);
            assertThat(metrics.find(code)).as("%s must be null on a LESSOR run", code).isNull();
        }
    }

    /** The constructor default: a seven-argument context is the lessor view, never {@code null}. */
    @Test
    void a_context_built_without_a_perspective_reads_the_lessor_side() {
        Schedule schedule = flatLoanSchedule();
        MetricContext context = new MetricContext(flowsOf(schedule), schedule, element(), 12, null,
                null, null);

        assertThat(context.perspective()).isEqualTo(MetricPerspective.LESSOR);
    }

    /**
     * A {@code null} perspective must never blow up a metric run (the constructor-tolerance rule
     * the class javadoc already applies to a missing schedule and element): it normalises to
     * {@link MetricPerspective#LESSOR}.
     */
    @Test
    void a_null_perspective_normalises_to_lessor() {
        Schedule schedule = flatLoanSchedule();
        MetricContext context = new MetricContext(flowsOf(schedule), schedule, element(), 12, null,
                null, null, null);

        assertThat(context.perspective()).isEqualTo(MetricPerspective.LESSOR);
        assertThat(MetricRegistry.defaults().computeAll(context).find(
                MetricCodes.BORROWER_IRR_ANNUAL)).isNull();
    }

    /**
     * The highest-risk line of the task: the per-run copy must carry the perspective, or the
     * tier-20/30 group silently reads the lessor view while the caller asked for the lessee one.
     */
    @Test
    void the_per_run_copy_keeps_the_perspective() {
        Schedule schedule = flatLoanSchedule();
        MetricContext lessee = new MetricContext(flowsOf(schedule), schedule, element(), 12, null,
                null, null, MetricPerspective.LESSEE);

        MetricContext run = lessee.withRunValues(Collections.<String, BigDecimal>emptyMap());

        assertThat(run.perspective()).isEqualTo(MetricPerspective.LESSEE);
        assertThat(run.cashFlows()).isEqualTo(lessee.cashFlows());
    }

    // ---------------------------------------------------------------- LESSEE view

    /**
     * The LESSEE run publishes the borrower family with real values and keeps the §9.5 yield family
     * present — the two families coexist in one bag under the plain {@link MetricCodes} keys.
     *
     * <p>Hand-computed ({@code M} = 12 periods per year, root {@code i} = 0.0049999991):
     * <ul>
     *   <li>{@code BORROWER_TOTAL_REPAYMENT} = Σ rentDue + Σ fee + Σ tax + 0 + 0 =
     *       1 032 797.15 (the fixture carries no residual and no deposit);</li>
     *   <li>{@code BORROWER_IRR_PERIOD} = {@code i} — the flipped flow set has the same root;</li>
     *   <li>{@code BORROWER_IRR_ANNUAL} = (1 + i)^12 − 1 = 0.061677800455;</li>
     *   <li>{@code BORROWER_IRR_ANNUAL_SIMPLE} = i × 12 = 0.059999989200.</li>
     * </ul>
     */
    @Test
    void a_lessee_run_publishes_the_borrower_family_next_to_the_lessor_yields() {
        MetricContext context = context(flatLoanSchedule(), MetricPerspective.LESSEE);
        FinancialMetrics metrics = MetricRegistry.defaults().computeAll(context);

        assertThat(metrics.find(MetricCodes.BORROWER_TOTAL_REPAYMENT))
                .isEqualByComparingTo("1032797.15");
        assertThat(metrics.find(MetricCodes.BORROWER_TOTAL_REPAYMENT).scale()).isEqualTo(2);
        assertThat(metrics.find(MetricCodes.BORROWER_IRR_ANNUAL))
                .isEqualByComparingTo("0.061677800455");
        assertThat(metrics.find(MetricCodes.BORROWER_IRR_ANNUAL_SIMPLE))
                .isEqualByComparingTo("0.059999989200");

        // The §9.5 family is still present, and its root is the same root: the mirror flips the
        // direction of every flow, which negates NPV and leaves its zeros where they were.
        for (String code : Arrays.asList(MetricCodes.IRR_PERIOD, MetricCodes.IRR_ANNUAL,
                MetricCodes.IRR_ANNUAL_SIMPLE, MetricCodes.XIRR, MetricCodes.TRUE_ANNUAL_RATE,
                MetricCodes.MIRR, MetricCodes.NPV, MetricCodes.XNPV)) {
            assertThat(metrics.find(code)).as(code).isNotNull();
        }
        assertThat(metrics.find(MetricCodes.IRR_ANNUAL))
                .isEqualByComparingTo(metrics.find(MetricCodes.BORROWER_IRR_ANNUAL));
        assertThat(metrics.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.15");
    }

    /**
     * The flip is a direction flip, not a sign on the amount, and the metrics read it: at a
     * configured per-period rate the lessee NPV is the negation of the lessor one, and a flow set
     * that starts with money in (the lessee's funded principal) has no payback period at all —
     * §9.5's「无回收期 → null」, not 0.
     */
    @Test
    void the_lessee_view_mirrors_the_present_value_and_has_no_payback() {
        Schedule schedule = flatLoanSchedule();
        List<CashFlow> flows = flowsOf(schedule);
        BigDecimal rate = new BigDecimal("0.005");

        BigDecimal lessorNpv = MetricRegistry.defaults()
                .computeAll(new MetricContext(flows, schedule, element(), 12, rate, null, null,
                        MetricPerspective.LESSOR))
                .find(MetricCodes.NPV);
        FinancialMetrics lessee = MetricRegistry.defaults()
                .computeAll(new MetricContext(flows, schedule, element(), 12, rate, null, null,
                        MetricPerspective.LESSEE));

        assertThat(lessee.find(MetricCodes.NPV)).isEqualByComparingTo(lessorNpv.negate());
        // -1 000 000 in, +86 066.43 … out: the running total after the first flow is positive,
        // so there is nothing to recover and both walks answer null.
        assertThat(lessee.find(MetricCodes.PAYBACK_PERIOD)).isNull();
        assertThat(lessee.find(MetricCodes.DISCOUNTED_PAYBACK)).isNull();
    }

    /**
     * The two annualizations consume the root the period metric published — they never solve a
     * second time (§9.5: one root solve per schedule). Pinned as the exact transformations of the
     * published {@code BORROWER_IRR_PERIOD}, not as an independent solve.
     */
    @Test
    void the_borrower_annualizations_read_the_recorded_root() {
        FinancialMetrics metrics = MetricRegistry.defaults()
                .computeAll(context(flatLoanSchedule(), MetricPerspective.LESSEE));

        BigDecimal period = metrics.find(MetricCodes.BORROWER_IRR_PERIOD);
        assertThat(period).isEqualByComparingTo("0.0049999991");
        assertThat(metrics.find(MetricCodes.BORROWER_IRR_ANNUAL)).isEqualByComparingTo(
                IrrCalculator.annualize(period, 12));
        assertThat(metrics.find(MetricCodes.BORROWER_IRR_ANNUAL_SIMPLE)).isEqualByComparingTo(
                IrrCalculator.simpleAnnualize(period, 12));
    }

    /** No root in the borrower view leaves the whole borrower yield family absent, never zero. */
    @Test
    void a_borrower_view_without_a_root_leaves_all_three_yield_codes_null() {
        Schedule schedule = scheduleWithSingleInflowOnly();
        MetricContext context = new MetricContext(schedule.cashFlows(), schedule, element(), 12,
                null, null, null, MetricPerspective.LESSEE);

        FinancialMetrics metrics = MetricRegistry.defaults().computeAll(context);

        assertThat(metrics.find(MetricCodes.BORROWER_IRR_PERIOD)).isNull();
        assertThat(metrics.find(MetricCodes.BORROWER_IRR_ANNUAL)).isNull();
        assertThat(metrics.find(MetricCodes.BORROWER_IRR_ANNUAL_SIMPLE)).isNull();
        // The schedule-derived borrower code needs no yield and is still there.
        assertThat(metrics.find(MetricCodes.BORROWER_TOTAL_REPAYMENT))
                .isEqualByComparingTo("1032797.15");
    }

    // ---------------------------------------------------------------- BORROWER_TOTAL_REPAYMENT

    /**
     * Hand-built 3-period schedule, hand-computed total (P-08: sum the details, round once):
     *
     * <pre>
     * period   principal   interest   fee   tax   rentDue
     *   1       30 000.00    500.00  100.00  60.00   30 660.00
     *   2       30 000.00    350.00  100.00  60.00   30 510.00
     *   3       40 000.00    200.00  100.00  60.00   40 360.00
     *   Σ       100 000.00  1 050.00  300.00 180.00  101 530.00
     * </pre>
     *
     * <p>plus the 5 000.00 residual settled separately ({@link ResidualHandling#SEPARATE}) and the
     * 10 000.00 deposit collected separately = <b>117 010.00</b>. The deposit basis matters: a
     * refundable deposit is handed over at t0 and returned at maturity, so the borrower's gross
     * outlay counts it once (v1 has no basis that nets it off the funded principal — see the
     * metric's javadoc).
     *
     * <p>The fee and tax columns are added <em>on top of</em> {@code rentDue} because R103 defines
     * the total that way; this fixture's rows are the ones a pre-FeeAllocator schedule could carry
     * (the v1 generators emit zero fees in the schedule and no separate VAT column), so the pinned
     * 117 010.00 is the brief's arithmetic rather than a claim that a real rent double counts its
     * own VAT.
     */
    @Test
    void total_repayment_adds_the_separately_paid_residual_and_the_deposit() {
        LeaseElement element = element().toBuilder()
                .periods(3)
                .residualValue(new BigDecimal("5000"))
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(new BigDecimal("10000"))
                .build();
        Schedule schedule = handWrittenSchedule();

        FinancialMetrics metrics = MetricRegistry.defaults().computeAll(new MetricContext(
                Collections.<CashFlow>emptyList(), schedule, element, 12, null, null, null,
                MetricPerspective.LESSEE));

        assertThat(metrics.find(MetricCodes.BORROWER_TOTAL_REPAYMENT))
                .isEqualByComparingTo("117010.00");
        assertThat(metrics.find(MetricCodes.BORROWER_TOTAL_REPAYMENT).scale()).isEqualTo(2);
    }

    /** Under {@link ResidualHandling#BALLOON_IN_RENT} the residual is inside the rents: +0 here. */
    @Test
    void total_repayment_does_not_double_count_a_balloon_residual() {
        LeaseElement element = element().toBuilder()
                .periods(3)
                .residualValue(new BigDecimal("5000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .deposit(new BigDecimal("10000"))
                .build();
        Schedule schedule = handWrittenSchedule();

        FinancialMetrics metrics = MetricRegistry.defaults().computeAll(new MetricContext(
                Collections.<CashFlow>emptyList(), schedule, element, 12, null, null, null,
                MetricPerspective.LESSEE));

        // 101 530.00 rents + 300.00 fees + 180.00 tax + 0.00 residual + 10 000.00 deposit.
        assertThat(metrics.find(MetricCodes.BORROWER_TOTAL_REPAYMENT))
                .isEqualByComparingTo("112010.00");
    }

    /** Null-safety mirrors {@code TotalMetrics}: no schedule, no total — never a fabricated 0.00. */
    @Test
    void total_repayment_is_absent_without_a_schedule_and_on_a_lessor_run() {
        FinancialMetrics lessor = MetricRegistry.defaults().computeAll(new MetricContext(
                Collections.<CashFlow>emptyList(), handWrittenSchedule(), element(), 12, null, null,
                null, MetricPerspective.LESSOR));
        assertThat(lessor.find(MetricCodes.BORROWER_TOTAL_REPAYMENT)).isNull();

        FinancialMetrics noSchedule = MetricRegistry.defaults().computeAll(new MetricContext(
                Collections.<CashFlow>emptyList(), null, element(), 12, null, null, null,
                MetricPerspective.LESSEE));
        assertThat(noSchedule.find(MetricCodes.BORROWER_TOTAL_REPAYMENT)).isNull();
    }

    // ---------------------------------------------------------------- fixtures

    private static MetricContext context(Schedule schedule, MetricPerspective perspective) {
        List<CashFlow> flows = schedule.cashFlows().isEmpty() ? flowsOf(schedule)
                : schedule.cashFlows();
        return new MetricContext(flows, schedule, element(), 12, null, null, null, perspective);
    }

    private static Schedule flatLoanSchedule() {
        return new EqualInstallmentGenerator().generate(new CalculationContext(element(), null,
                new Thirty360(), new NoRoll(), SystemClockProvider.INSTANCE,
                CalculationOptions.defaults()));
    }

    /** The same golden flow set with every direction forced in: no sign change, hence no root. */
    private static Schedule scheduleWithSingleInflowOnly() {
        Schedule flat = flatLoanSchedule();
        List<CashFlow> inflows = new ArrayList<>();
        for (CashFlow flow : flowsOf(flat)) {
            inflows.add(new CashFlow(flow.sequence(), flow.date(), flow.amount(), flow.type(),
                    CashFlowDirection.INFLOW));
        }
        return flat.with(FinancialMetrics.empty(), inflows);
    }

    private static List<CashFlow> flowsOf(Schedule schedule) {
        return new StandardCashFlowBuilder().build(schedule, element(),
                CalculationOptions.defaults());
    }

    /** The three hand-written rows of the {@code BORROWER_TOTAL_REPAYMENT} arithmetic. */
    private static Schedule handWrittenSchedule() {
        List<Installment> rows = new ArrayList<>();
        rows.add(row(1, "100000.00", "30000.00", "500.00", "30660.00", "70000.00"));
        rows.add(row(2, "70000.00", "30000.00", "350.00", "30510.00", "40000.00"));
        rows.add(row(3, "40000.00", "40000.00", "200.00", "40360.00", "0.00"));
        ScheduleHeader header = new ScheduleHeader(FinancialMetrics.empty(), rows.size(), T0,
                LocalDate.of(2024, 4, 1), "HAND_WRITTEN", null);
        return new Schedule(header, rows, Collections.<CashFlow>emptyList(),
                Collections.<ValidationIssue>emptyList());
    }

    private static Installment row(int periodNo, String opening, String principal, String interest,
            String rent, String closing) {
        BigDecimal fee = new BigDecimal("100.00");
        BigDecimal tax = new BigDecimal("60.00");
        return new Installment(periodNo, T0.plusMonths(periodNo - 1L), T0.plusMonths(periodNo), 30,
                new BigDecimal(opening), new BigDecimal(principal), new BigDecimal(interest),
                new BigDecimal(interest), fee, tax, new BigDecimal(rent), new BigDecimal(closing),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.06"), null);
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
