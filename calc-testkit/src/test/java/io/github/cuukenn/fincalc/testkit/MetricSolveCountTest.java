package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.metric.MetricRegistry;
import io.github.cuukenn.fincalc.domain.metric.impl.IrrMetrics;
import io.github.cuukenn.fincalc.domain.metric.impl.LeaseImplicitRateMetric;
import io.github.cuukenn.fincalc.domain.metric.impl.XirrMetric;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.port.CashFlowBuilder;
import io.github.cuukenn.fincalc.domain.support.BisectionNewtonSolver;
import io.github.cuukenn.fincalc.domain.support.DateRollConventions;
import io.github.cuukenn.fincalc.domain.support.DayCountConventions;
import io.github.cuukenn.fincalc.domain.support.cashflow.StandardCashFlowBuilder;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import io.github.cuukenn.fincalc.engine.GeneratorRegistry;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A run must be able to prove how many root solves it paid for. This test
 * reads {@link BisectionNewtonSolver#SOLVE_COUNT} / {@link BisectionNewtonSolver#EVALUATION_COUNT}
 * around the two orchestration shapes and pins the expected counts:
 *
 * <ul>
 *   <li>the §9.5 metric line of a LESSOR run solves exactly twice — {@code IRR_PERIOD} (period
 *       discounting) and {@code XIRR} (day discounting). Both are distinct §9.5 codes; a third
 *       solve here would be a duplicate root.</li>
 *   <li>the engine's §9.7 chain reads the run's root instead of solving its own stream a second
 *       time, exactly when the two streams are the same root problem — and pays its own solve when
 *       they are not.</li>
 * </ul>
 *
 * <p>The full-run counts are therefore pinned both ways: two solves for a deal the §9.4 event table
 * and the §9.7 rent-caliber stream agree on, three for one carrying residual money the event table
 * deliberately does not list. A count that never moved would not tell the two apart.
 */
class MetricSolveCountTest {

    private static final CashFlowBuilder CASH_FLOW_BUILDER = new StandardCashFlowBuilder();
    private static final MetricRegistry METRICS = MetricRegistry.defaults();

    @Test
    void the_metric_line_solves_exactly_the_roots_it_must_and_no_more() {
        LeaseElement element = element480();
        MetricContext metricContext = metricContext(element);

        long solvesBefore = BisectionNewtonSolver.SOLVE_COUNT.get();
        long evalsBefore = BisectionNewtonSolver.EVALUATION_COUNT.get();
        long start = System.nanoTime();
        FinancialMetrics metrics = METRICS.computeAll(metricContext);
        long elapsed = System.nanoTime() - start;
        long solves = BisectionNewtonSolver.SOLVE_COUNT.get() - solvesBefore;
        long evals = BisectionNewtonSolver.EVALUATION_COUNT.get() - evalsBefore;

        System.out.printf("[solve-count] 480p staged metric line: %d solves, %d evals, %.1f ms%n",
                solves, evals, elapsed / 1_000_000.0);
        assertThat(solves).as("§9.5 line of a LESSOR run: IRR_PERIOD + XIRR, no third root")
                .isEqualTo(2);
        assertThat(metrics.find("IRR_PERIOD")).isNotNull();

        // Split: where do the evals go — the periodic root or the day-discounted one?
        countOneFamily("IRR family (IRR_PERIOD)", IrrMetrics.instances().subList(0, 1));
        countOneFamily("XIRR family (XIRR)", XirrMetric.instances().subList(0, 1));
    }

    @Test
    void the_full_run_pays_for_every_root_it_solves_and_the_count_is_verifiable() {
        CalculationFacade facade = CalculationFacade.defaults();

        long solvesBefore = BisectionNewtonSolver.SOLVE_COUNT.get();
        long evalsBefore = BisectionNewtonSolver.EVALUATION_COUNT.get();
        long start = System.nanoTime();
        CalculationResult result = facade.calculate(
                new CalculationRequest(element480(), null, CalculationOptions.defaults()));
        long elapsed = System.nanoTime() - start;
        long solves = BisectionNewtonSolver.SOLVE_COUNT.get() - solvesBefore;
        long evals = BisectionNewtonSolver.EVALUATION_COUNT.get() - evalsBefore;

        System.out.printf("[solve-count] 480p full run: %d solves, %d evals, %.1f ms%n",
                solves, evals, elapsed / 1_000_000.0);
        assertThat(result.schedule().installments()).hasSize(480);
    }

    /**
     * 「分段计时可读」taken one level finer: which code of the §9.5 line costs
     * what. The registered set is wrapped in timing decorators, same codes, same order, same
     * compute — so the run is the run the facade pays for, and the table is what a hotspot ordering
     * has to be read from. Printed, not asserted: a per-code assertion would be a second gate on
     * numbers this machine cannot hold still, and the cost gate lives in
     * {@link RunCostBaselineTest}.
     */
    @Test
    void the_metric_line_costs_are_attributable_to_each_code() {
        LeaseElement element = element480();

        Map<String, Long> costs = new LinkedHashMap<>();
        MetricRegistry timed = MetricRegistry.empty();
        for (FinancialMetric metric : METRICS.metrics()) {
            timed.register(timed(metric, costs));
        }
        // One warm pass so the first pass's class loading does not tilt the table.
        timed.computeAll(metricContext(element));
        costs.clear();

        MetricContext context = metricContext(element);
        long start = System.nanoTime();
        timed.computeAll(context);
        long elapsed = System.nanoTime() - start;

        System.out.printf("[metric-cost] 480p §9.5 line, %.1f ms total%n", elapsed / 1_000_000.0);
        costs.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(entry -> System.out.printf("[metric-cost]   %-26s %8.1f ms%n",
                        entry.getKey(), entry.getValue() / 1_000_000.0));
        assertThat(costs).hasSize(METRICS.metrics().size());
    }

    private static FinancialMetric timed(FinancialMetric delegate,
            Map<String, Long> costs) {
        return new FinancialMetric() {
            @Override
            public String code() {
                return delegate.code();
            }

            @Override
            public int order() {
                return delegate.order();
            }

            @Override
            public BigDecimal compute(MetricContext context) {
                long before = System.nanoTime();
                try {
                    return delegate.compute(context);
                } finally {
                    costs.merge(delegate.code(), System.nanoTime() - before, Long::sum);
                }
            }
        };
    }

    private static void countOneFamily(String label, List<FinancialMetric> family) {
        MetricContext metricContext = metricContext(element480());

        MetricRegistry only = MetricRegistry.empty();
        for (FinancialMetric metric : family) {
            only.register(metric);
        }
        long solvesBefore = BisectionNewtonSolver.SOLVE_COUNT.get();
        long evalsBefore = BisectionNewtonSolver.EVALUATION_COUNT.get();
        long start = System.nanoTime();
        only.computeAll(metricContext);
        long elapsed = System.nanoTime() - start;

        System.out.printf("[solve-count] %s: %d solves, %d evals, %.1f ms%n", label,
                BisectionNewtonSolver.SOLVE_COUNT.get() - solvesBefore,
                BisectionNewtonSolver.EVALUATION_COUNT.get() - evalsBefore,
                elapsed / 1_000_000.0);
    }

    /**
     * Ticket 02 / the §9.7 effective-interest chain prices the stream the run has
     * already solved. On a plain deal — no upfront fee, no deposit leg, no separately settled 留购价,
     * no residual money — the chain's t0 opening balance and rent legs <em>are</em> the §9.4 event
     * table {@code IRR_PERIOD} was solved over, index for index, so the run pays two solves and not
     * three. Before the hand-off existed this shape paid three: the same root, twice.
     */
    @Test
    void the_engine_reads_the_runs_own_root_when_the_two_streams_are_one_root_problem() {
        CalculationFacade facade = CalculationFacade.defaults();

        long solvesBefore = BisectionNewtonSolver.SOLVE_COUNT.get();
        long evalsBefore = BisectionNewtonSolver.EVALUATION_COUNT.get();
        CalculationResult result = facade.calculate(
                new CalculationRequest(element480(), null, CalculationOptions.defaults()));
        long solves = BisectionNewtonSolver.SOLVE_COUNT.get() - solvesBefore;
        long evals = BisectionNewtonSolver.EVALUATION_COUNT.get() - evalsBefore;

        System.out.printf("[solve-count] 480p full run, §9.7 stream = event table: "
                + "%d solves, %d evals%n", solves, evals);
        assertThat(result.effectiveInterest()).as("the chain still runs; only its root is reused")
                .isNotNull();
        assertThat(solves).as("IRR_PERIOD + XIRR only: the §9.7 chain reads the run's root")
                .isEqualTo(2);
    }

    /**
     * The other side of the same ruling: when the two streams are not one root problem, the run
     * earns its own solve rather than borrow a root that is not a root of it. Here the divergence is
     * the unguaranteed residual — CAS 21 第三十八条 prices it as part of the lessor's net
     * investment, while §9.4 keeps the event table cash-only — so it appears in the implicit-rate
     * stream's terminal leg and nowhere in the flows {@code IRR_PERIOD} solved over. Reusing the
     * run's first root there would price the wrong stream at a rate that does not zero it.
     *
     * <p>What the ledger removes is the <em>second</em> duplicate: the implicit-rate metric solves
     * this stream once and records it, and the §9.7 chain — which prices the identical net
     * investment — reads that root off the ledger instead of solving it again. Before the ledger
     * this deal paid four solves; the three left are each a different root problem.
     */
    @Test
    void a_stream_that_differs_from_the_event_table_earns_its_own_stated_solve() {
        LeaseElement element = Fixtures
                .leaseElement(new BigDecimal("1000000"), new BigDecimal("0.0475"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360")
                .toBuilder()
                .assetValue(new BigDecimal("1000000"))
                .unguaranteedResidual(new BigDecimal("40000"))
                .build();

        long solvesBefore = BisectionNewtonSolver.SOLVE_COUNT.get();
        CalculationResult result = CalculationFacade.defaults()
                .calculate(new CalculationRequest(element, null, CalculationOptions.defaults()));
        long solves = BisectionNewtonSolver.SOLVE_COUNT.get() - solvesBefore;

        System.out.printf("[solve-count] 12p + 未担保余值: %d solves%n", solves);
        assertThat(result.effectiveInterest()).as("the chain priced its own stream").isNotNull();
        assertThat(solves).as("IRR_PERIOD + XIRR + one net-investment root shared by "
                + "LEASE_IMPLICIT_RATE and the §9.7 chain").isEqualTo(3);
    }

    /**
     * The equality the implicit-rate javadoc states, pinned as a solve count: with no unguaranteed
     * residual and asset value = principal, the CAS 21 measure chain's stream <em>is</em> the §9.4
     * event table — so {@code LEASE_IMPLICIT_RATE} reads {@code IRR_PERIOD}'s unrounded root off the
     * ledger and the staged line pays two solves, not three. A duplicated solve here would be the
     * exact duplicate the one-solve rule forbids: same stream, same root, no caliber difference to state.
     */
    @Test
    void the_implicit_rate_reads_the_runs_root_when_its_stream_is_the_event_table() {
        LeaseElement element = Fixtures
                .leaseElement(new BigDecimal("1000000"), new BigDecimal("0.0475"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360")
                .toBuilder()
                .assetValue(new BigDecimal("1000000"))
                .build();

        long solvesBefore = BisectionNewtonSolver.SOLVE_COUNT.get();
        FinancialMetrics metrics = METRICS.computeAll(metricContext(element));
        long solves = BisectionNewtonSolver.SOLVE_COUNT.get() - solvesBefore;

        System.out.printf("[solve-count] 12p + 资产价值, 无未担保余值: %d solves%n", solves);
        assertThat(metrics.find("LEASE_IMPLICIT_RATE")).isNotNull();
        // The stated equality holds up to the publication rounding, not beyond it: IRR_ANNUAL
        // annualizes the *published* scale-10 IRR_PERIOD (the bag's own contract — the same number
        // the output carries), while the implicit rate annualizes the ledger's unrounded root. One
        // half-ulp of periodic rounding δ ≤ 5e-11 amplifies through annualization to at most
        // m·(1+r)^{m-1}·δ ≈ 6e-10 at monthly m = 12, so a gap under 1e-8 is the equality; a
        // bit-for-bit assertion would pin two different rounding contracts against each other.
        assertThat(metrics.find("LEASE_IMPLICIT_RATE").subtract(metrics.find("IRR_ANNUAL")).abs())
                .as("implicit rate = IRR_ANNUAL within the annualized publication rounding")
                .isLessThan(new BigDecimal("1e-8"));
        assertThat(solves).as("IRR_PERIOD + XIRR; the implicit rate reads the run's root")
                .isEqualTo(2);
    }

    /**
     * A code that cannot compute must say so before spending a solve. {@code LEASE_IMPLICIT_RATE}
     * needs the asset value CAS 21 measures against; with none in the element it answers
     * {@code null} and the run pays for nothing — a solver call on a stream whose answer is already
     * known to be unusable is the duplicate the one-solve rule forbids, not a harmless probe.
     */
    @Test
    void lease_implicit_rate_declares_itself_uncomputable_before_any_solve() {
        MetricRegistry only = MetricRegistry.empty();
        only.register(new LeaseImplicitRateMetric());

        long solvesBefore = BisectionNewtonSolver.SOLVE_COUNT.get();
        FinancialMetrics metrics = only.computeAll(metricContext(element480()));
        long solves = BisectionNewtonSolver.SOLVE_COUNT.get() - solvesBefore;

        System.out.printf("[solve-count] LEASE_IMPLICIT_RATE alone: %d solves%n", solves);
        assertThat(solves).isZero();
        assertThat(metrics.find("LEASE_IMPLICIT_RATE")).isNull();
    }

    private static LeaseElement element480() {
        return Fixtures.leaseElement(new BigDecimal("2380000"), new BigDecimal("0.035"), 480,
                RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360");
    }

    private static MetricContext metricContext(LeaseElement element) {
        Schedule schedule = GeneratorRegistry.defaults()
                .require(RepaymentMethodId.EQUAL_INSTALLMENT.name())
                .generate(context(element));
        return new MetricContext(
                CASH_FLOW_BUILDER.build(schedule, element, CalculationOptions.defaults()),
                schedule, element, element.frequency().periodsPerYear(), null, null, null);
    }

    private static CalculationContext context(LeaseElement element) {
        return new CalculationContext(element, null,
                DayCountConventions.all().require(element.dayCountCode()),
                DateRollConventions.all().require(element.dateRollCode()),
                SystemClockProvider.INSTANCE, CalculationOptions.defaults());
    }
}
