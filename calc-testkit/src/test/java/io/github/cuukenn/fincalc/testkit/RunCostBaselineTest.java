package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.metric.MetricRegistry;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.InterestTreatmentKind;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PrincipalPlanKind;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.SpecialActionKind;
import io.github.cuukenn.fincalc.domain.port.CashFlowBuilder;
import io.github.cuukenn.fincalc.domain.port.DateRollConvention;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import io.github.cuukenn.fincalc.domain.port.ScheduleGenerator;
import io.github.cuukenn.fincalc.domain.support.DateRollConventions;
import io.github.cuukenn.fincalc.domain.support.DayCountConventions;
import io.github.cuukenn.fincalc.domain.support.ValidatorChain;
import io.github.cuukenn.fincalc.domain.support.cashflow.StandardCashFlowBuilder;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import io.github.cuukenn.fincalc.engine.GeneratorRegistry;
import io.github.cuukenn.fincalc.rule.compiler.RuleCompiler;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import io.github.cuukenn.fincalc.rule.model.CompiledRuleSet;
import io.github.cuukenn.fincalc.rule.model.RuleRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AGENTS.md §6 N-01: the run-cost gate. Before this test existed the repository had no repeatable
 * timing reading at all — the design document's own gate G6 (120 periods, P99 &lt; 50 ms) was nothing
 * but a sentence, and nothing noticed when the engine drifted past it.
 *
 * <p>Four shapes are pinned, in order:
 * <ol>
 *   <li>120 periods, fixed rate, no rule set — the cell G6 talks about;</li>
 *   <li>480 periods (the UI ceiling), fixed rate, no rule set;</li>
 *   <li>480 periods with 160 three-month rate-segment rows — the mortgage repricing shape of
 *       ticket 02 / R150, i.e. a segmented run that keeps the closed-form rent;</li>
 *   <li>1200 periods (the engine ceiling, {@code LeaseElement.MAX_PERIODS}) with 400 segments under
 *       {@code ACT_360} — the worst realistic combination, where every segment re-solves through the
 *       iterative rent solver.</li>
 * </ol>
 *
 * <p><b>These gates are non-regression ceilings, not the design target.</b> They were read off one
 * measuring pass on 2026-11-12 (Java 11, warm JVM, best of N) with a wide margin over the readings,
 * so a normal machine under load still passes while a real regression cannot hide. G6 is NOT claimed
 * as met here — N-02 forbids writing it as achieved until the metric-layer work (tickets 02/03 of
 * {@code .scratch/calc-perf}) actually closes the gap.
 *
 * <p>How the stage breakdown is obtained without touching production code: the orchestration steps
 * are called one by one — generator (step 2), event table (step 3), metrics plus the enriched
 * schedule (steps 4 and 5, folded together because {@code Schedule.with} is part of metric
 * assembly), validator chain (step 6) — and the whole run is timed separately through
 * {@link CalculationFacade#calculate}. Each printed column is the best of the samples, so a row's
 * stages are not required to add up to its total. The 1200-period cell is gated on its stages only:
 * on top of them the facade adds the §9.7 table and the result assembly, which is a constant-order
 * addition there and not what that cell is guarding.
 *
 * <p>Every measured shape is also checked for correctness (period count, no ERROR finding, tail
 * balance zero, and for the segmented runs the per-segment applied rates): a gate that a broken
 * schedule could pass by being fast is not a gate.
 */
class RunCostBaselineTest {

    /** Principal of the mortgage-sized fixture every cell uses. */
    private static final BigDecimal PRINCIPAL = new BigDecimal("2380000");

    /** Nominal annual rate of the fixture (3.5%, LPR-ish). */
    private static final BigDecimal NOMINAL_RATE = new BigDecimal("0.035");

    /** Repricing cycle of the segmented cells, in months (ticket 02 / R150 shape). */
    private static final int CYCLE_MONTHS = 3;

    private static final CalculationOptions OPTIONS = CalculationOptions.defaults();
    private static final CashFlowBuilder CASH_FLOW_BUILDER = new StandardCashFlowBuilder();
    private static final MetricRegistry METRICS = MetricRegistry.defaults();
    private static final ValidatorChain VALIDATORS = ValidatorChain.defaults();

    /**
     * Gates in milliseconds. Column order is fixed:
     * {@code schedule} (amortization plan) / {@code cashFlows} (§9.4 event table) /
     * {@code metrics} (§9.5 assembly plus {@code Schedule.with}) / {@code validators} (§3.4 chain) /
     * {@code stages} (best whole-pass stage sum) / {@code fullRun} ({@code calculate} end to end,
     * {@code 0} = that cell does not gate the whole run).
     */
    private static final Gates GATE_120_FIXED = new Gates(150, 150, 1000, 250, 1400, 1200);
    private static final Gates GATE_480_FIXED = new Gates(300, 300, 4000, 500, 5200, 5000);
    private static final Gates GATE_480_SEGMENTED = new Gates(700, 300, 4000, 500, 5200, 5000);

    /**
     * The worst cell's schedule gate carries the largest margin on purpose: 2026-11-12 readings put
     * its amortization stage at 6.0 s, which is where each of the 400 segments re-solves the rent
     * through the iterative solver. That stage is what ticket 04 (item b) exists to make cheaper, so
     * the gate is a ceiling with room, not a blessing of the current cost.
     */
    private static final Gates GATE_1200_SEGMENTED = new Gates(9000, 1000, 12000, 1000, 25000, 0);

    /**
     * Relative gate on the segmented closed form: 160 rate-segment rows must not move a run that
     * keeps the closed-form rent (measured 2026-11-12: 1755 ms against 1743 ms). A ratio gate is
     * machine-tolerant in a way an absolute ceiling is not, and it is the reading that catches the
     * day-count question ticket 04 raises — switch a monthly mortgage to {@code ACT_360} and the
     * same 160 segments stop being free.
     */
    private static final double SEGMENTED_VS_FIXED_RATIO = 1.35;

    /**
     * Relative gate on the worst cell: {@code ACT_360} with 400 segments at the engine period ceiling
     * may cost at most this multiple of the closed-form segmented run. Calibration history: measured
     * 6.0x on 2026-11-12 (gate set to 8.0); recalibrated on 2026-11-14 after the §9.5 metric-line
     * optimization — the numerator's metric stage shrank ~12% while the denominator's full run
     * (nine-tenths metrics) shrank ~38%, so the honest ratio rose to ~8.0x and the gate follows the
     * measurement (10.0 = measured 8.0 × margin), not the stale calibration.
     */
    private static final double ACT360_SEGMENTED_VS_CLOSED_FORM_RATIO = 10.0;

    @Test
    void the_four_pinned_shapes_stay_inside_their_run_cost_gates() {
        Scenario fixed120 = Scenario.fixed("120p THIRTY_360 fixed", 120, "THIRTY_360",
                2, 3, GATE_120_FIXED);
        Scenario fixed480 = Scenario.fixed("480p THIRTY_360 fixed", 480, "THIRTY_360",
                2, 2, GATE_480_FIXED);
        Scenario segmented480 = Scenario.segmented("480p THIRTY_360 + 160 segments", 480, "THIRTY_360",
                160, 2, 2, GATE_480_SEGMENTED);
        Scenario segmented1200 = Scenario.segmented("1200p ACT_360 + 400 segments", 1200, "ACT_360",
                400, 1, 2, GATE_1200_SEGMENTED);

        System.out.println("[calc-perf] java=" + System.getProperty("java.version")
                + " cores=" + Runtime.getRuntime().availableProcessors()
                + " — milliseconds, best of N after warm-up; gates are non-regression ceilings,"
                + " not the G6 target (AGENTS.md §6 N-01/N-02)");
        System.out.printf("%-32s %5s %10s %9s %9s %9s %9s %9s%n", "shape", "rows", "schedule",
                "cashflow", "metrics", "validate", "stages", "fullRun");

        for (Scenario scenario : List.of(fixed120, fixed480, segmented480, segmented1200)) {
            measure(scenario);
        }

        assertThat(segmented480.fullRunMs)
                .as("160 rate-segment rows must not move a closed-form run "
                        + "(AGENTS.md §6 N-01; ticket 02's cost line)")
                .isLessThanOrEqualTo(fixed480.fullRunMs * SEGMENTED_VS_FIXED_RATIO);
        assertThat(segmented1200.stageSumMs)
                .as("ACT_360 at the engine period ceiling must stay within "
                        + ACT360_SEGMENTED_VS_CLOSED_FORM_RATIO + "x of the closed-form segmented run")
                .isLessThanOrEqualTo(segmented480.fullRunMs * ACT360_SEGMENTED_VS_CLOSED_FORM_RATIO);
    }

    /** Warm up, check the shape once, then time {@code samples} passes and record the best reading. */
    private static void measure(Scenario scenario) {
        Schedule warm = instrumented(scenario).validated;
        assertShape(scenario, warm);
        for (int i = 1; i < scenario.warmups; i++) {
            instrumented(scenario);
        }
        if (scenario.gate.fullRunMs > 0) {
            fullRun(scenario);
        }

        Timed best = null;
        double bestFullRun = Double.MAX_VALUE;
        for (int i = 0; i < scenario.samples; i++) {
            Timed sample = instrumented(scenario);
            if (scenario.gate.fullRunMs > 0) {
                bestFullRun = Math.min(bestFullRun, fullRun(scenario));
            }
            if (best == null || sample.stageSumMs < best.stageSumMs) {
                best = sample;
            }
        }

        scenario.scheduleMs = best.scheduleMs;
        scenario.cashFlowsMs = best.cashFlowsMs;
        scenario.metricsMs = best.metricsMs;
        scenario.validatorsMs = best.validatorsMs;
        scenario.stageSumMs = best.stageSumMs;
        scenario.fullRunMs = scenario.gate.fullRunMs > 0 ? bestFullRun : 0.0;

        System.out.printf("%-32s %5d %9.1f %9.1f %9.1f %9.1f %9.1f %9s%n", scenario.name,
                scenario.rows.size(), scenario.scheduleMs, scenario.cashFlowsMs, scenario.metricsMs,
                scenario.validatorsMs, scenario.stageSumMs,
                scenario.fullRunMs > 0 ? String.format("%.1f", scenario.fullRunMs) : "—");

        assertWithinGate("schedule", scenario.scheduleMs, scenario.gate.scheduleMs, scenario.name);
        assertWithinGate("cashFlows", scenario.cashFlowsMs, scenario.gate.cashFlowsMs, scenario.name);
        assertWithinGate("metrics", scenario.metricsMs, scenario.gate.metricsMs, scenario.name);
        assertWithinGate("validators", scenario.validatorsMs, scenario.gate.validatorsMs, scenario.name);
        assertWithinGate("stages", scenario.stageSumMs, scenario.gate.stagesMs, scenario.name);
        if (scenario.gate.fullRunMs > 0) {
            assertWithinGate("fullRun", scenario.fullRunMs, scenario.gate.fullRunMs, scenario.name);
        }
    }

    private static void assertWithinGate(String stage, double reading, double gate, String shape) {
        assertThat(reading)
                .as(String.format("%s / %s stayed inside the AGENTS.md §6 N-01 gate (%.0f ms)",
                        shape, stage, gate))
                .isLessThanOrEqualTo(gate);
    }

    /**
     * One pass over the orchestration steps, each timed separately. The {@link Schedule} returned in
     * {@link Timed#validated} is the same object the facade hands the validator chain: the enriched
     * schedule carrying metrics and the event table.
     */
    private static Timed instrumented(Scenario scenario) {
        Timed timed = new Timed();

        long start = System.nanoTime();
        Schedule schedule = scenario.generator.generate(new CalculationContext(scenario.element,
                scenario.ruleSet, scenario.dayCount, scenario.dateRoll,
                SystemClockProvider.INSTANCE, OPTIONS));
        long afterSchedule = System.nanoTime();

        List<CashFlow> flows = CASH_FLOW_BUILDER.build(schedule, scenario.element, OPTIONS);
        long afterFlows = System.nanoTime();

        FinancialMetrics metrics = METRICS.computeAll(new MetricContext(flows, schedule,
                scenario.element, scenario.element.frequency().periodsPerYear(), null, null, null));
        Schedule enriched = schedule.with(metrics, flows);
        long afterMetrics = System.nanoTime();

        Schedule validated = VALIDATORS.validate(enriched, scenario.element);
        long afterValidators = System.nanoTime();

        timed.scheduleMs = millis(afterSchedule - start);
        timed.cashFlowsMs = millis(afterFlows - afterSchedule);
        timed.metricsMs = millis(afterMetrics - afterFlows);
        timed.validatorsMs = millis(afterValidators - afterMetrics);
        timed.stageSumMs = timed.scheduleMs + timed.cashFlowsMs + timed.metricsMs
                + timed.validatorsMs;
        timed.validated = validated;
        return timed;
    }

    /** The whole orchestration through the facade, including the §9.7 table and the result. */
    private static double fullRun(Scenario scenario) {
        long start = System.nanoTime();
        CalculationResult result = scenario.facade.calculate(
                new CalculationRequest(scenario.element, scenario.ruleSet, OPTIONS));
        long elapsed = System.nanoTime() - start;
        assertThat(result.schedule().installments()).hasSize(scenario.periods);
        return millis(elapsed);
    }

    /**
     * The shape the gate is guarding must also be a correct run: an equal-installment plan of the
     * pinned period count, no ERROR finding, a closed tail balance, and — for the segmented cells —
     * the segment's own rate on the first, second-segment and last periods.
     */
    private static void assertShape(Scenario scenario, Schedule schedule) {
        List<Installment> installments = schedule.installments();
        assertThat(installments).hasSize(scenario.periods);
        assertThat(schedule.hasErrors())
                .as(scenario.name + ": the pinned shape must not carry an ERROR finding")
                .isFalse();
        assertThat(installments.get(scenario.periods - 1).closingBalance())
                .as(scenario.name + ": tail balance")
                .isEqualByComparingTo("0.00");
        if (!scenario.segmentRates.isEmpty()) {
            assertThat(installments.get(0).appliedRate())
                    .isEqualByComparingTo(scenario.segmentRates.get(0));
            assertThat(installments.get(CYCLE_MONTHS).appliedRate())
                    .isEqualByComparingTo(scenario.segmentRates.get(1));
            assertThat(installments.get(scenario.periods - 1).appliedRate())
                    .isEqualByComparingTo(scenario.segmentRates.get(scenario.periods / CYCLE_MONTHS - 1));
        }
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /** Segmented rate rows: unconditional rate-only rows of {@code cycleMonths} periods each. */
    private static List<RuleRow> segmentRows(int periods, List<BigDecimal> rates) {
        List<RuleRow> rows = new ArrayList<>(rates.size());
        for (int i = 0; i < rates.size(); i++) {
            rows.add(new RuleRow("SEG_R" + (i + 1), 10 + 10 * i, i * CYCLE_MONTHS + 1,
                    Math.min((i + 1) * CYCLE_MONTHS, periods), rates.get(i), null,
                    PrincipalPlanKind.INHERIT, null, null, InterestTreatmentKind.PAY_AS_INCURRED,
                    PaymentTiming.IN_ARREARS, SpecialActionKind.NONE, null, 0, null, null, 0, null));
        }
        return rows;
    }

    /**
     * The alternating 3.50% / 3.60% segment rates of the repricing cells. The values do not matter
     * for the cost gate; what matters is that every segment changes the rate, which is what makes
     * each segment re-solve the rent under a {@code RESOLVE} policy.
     */
    private static List<BigDecimal> segmentRateValues(int segments) {
        List<BigDecimal> rates = new ArrayList<>(segments);
        for (int i = 0; i < segments; i++) {
            rates.add(i % 2 == 0 ? NOMINAL_RATE : NOMINAL_RATE.add(new BigDecimal("0.001")));
        }
        return rates;
    }

    /** One pinned run shape, with the readings the gate asserts on filled in by {@link #measure}. */
    private static final class Scenario {

        private final String name;
        private final int periods;
        private final LeaseElement element;
        private final List<RuleRow> rows;
        private final List<BigDecimal> segmentRates;
        private final CompiledRuleSet ruleSet;
        private final ScheduleGenerator generator;
        private final DayCountConvention dayCount;
        private final DateRollConvention dateRoll;
        private final CalculationFacade facade;
        private final int warmups;
        private final int samples;
        private final Gates gate;

        private double scheduleMs;
        private double cashFlowsMs;
        private double metricsMs;
        private double validatorsMs;
        private double stageSumMs;
        private double fullRunMs;

        /**
         * Whether this shape also gates the whole run is carried by {@code gate}: a gate whose
         * {@code fullRunMs} is zero means "stages only" (see {@link Gates}).
         */
        private Scenario(String name, int periods, String dayCountCode, int segments, int warmups,
                int samples, Gates gate) {
            this.name = name;
            this.periods = periods;
            this.element = Fixtures.leaseElement(PRINCIPAL, NOMINAL_RATE, periods,
                    RepaymentMethodId.EQUAL_INSTALLMENT.name(), dayCountCode);
            this.segmentRates = segments == 0 ? new ArrayList<>()
                    : segmentRateValues(periods / CYCLE_MONTHS);
            this.rows = segmentRows(periods, this.segmentRates);
            this.ruleSet = segments == 0 ? null
                    : new RuleCompiler(FunctionRegistry.defaults()).compile(this.rows, element, false);
            this.generator = GeneratorRegistry.defaults()
                    .require(RepaymentMethodId.EQUAL_INSTALLMENT.name());
            this.dayCount = DayCountConventions.all().require(element.dayCountCode());
            this.dateRoll = DateRollConventions.all().require(element.dateRollCode());
            this.facade = CalculationFacade.defaults();
            this.warmups = warmups;
            this.samples = samples;
            this.gate = gate;
        }

        private static Scenario fixed(String name, int periods, String dayCountCode, int warmups,
                int samples, Gates gate) {
            return new Scenario(name, periods, dayCountCode, 0, warmups, samples, gate);
        }

        private static Scenario segmented(String name, int periods, String dayCountCode, int segments,
                int warmups, int samples, Gates gate) {
            return new Scenario(name, periods, dayCountCode, segments, warmups, samples, gate);
        }
    }

    /** Stage readings of one instrumented pass. */
    private static final class Timed {

        private double scheduleMs;
        private double cashFlowsMs;
        private double metricsMs;
        private double validatorsMs;
        private double stageSumMs;
        private Schedule validated;
    }

    /** Non-regression ceilings of one shape, in milliseconds. */
    private static final class Gates {

        private final double scheduleMs;
        private final double cashFlowsMs;
        private final double metricsMs;
        private final double validatorsMs;
        private final double stagesMs;
        private final double fullRunMs;

        private Gates(double scheduleMs, double cashFlowsMs, double metricsMs, double validatorsMs,
                double stagesMs, double fullRunMs) {
            this.scheduleMs = scheduleMs;
            this.cashFlowsMs = cashFlowsMs;
            this.metricsMs = metricsMs;
            this.validatorsMs = validatorsMs;
            this.stagesMs = stagesMs;
            this.fullRunMs = fullRunMs;
        }
    }
}
