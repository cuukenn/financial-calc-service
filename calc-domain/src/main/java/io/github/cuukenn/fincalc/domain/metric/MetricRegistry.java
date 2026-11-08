package io.github.cuukenn.fincalc.domain.metric;

import io.github.cuukenn.fincalc.common.spi.ExtensionRegistry;
import io.github.cuukenn.fincalc.domain.metric.impl.AverageBalanceMetric;
import io.github.cuukenn.fincalc.domain.metric.impl.BorrowerIrrMetrics;
import io.github.cuukenn.fincalc.domain.metric.impl.BorrowerIrrPeriodMetric;
import io.github.cuukenn.fincalc.domain.metric.impl.BorrowerTotalRepaymentMetric;
import io.github.cuukenn.fincalc.domain.metric.impl.DurationMetrics;
import io.github.cuukenn.fincalc.domain.metric.impl.IrrMetrics;
import io.github.cuukenn.fincalc.domain.metric.impl.MirrMetric;
import io.github.cuukenn.fincalc.domain.metric.impl.NpvMetric;
import io.github.cuukenn.fincalc.domain.metric.impl.PaybackMetric;
import io.github.cuukenn.fincalc.domain.metric.impl.RentalRateMetric;
import io.github.cuukenn.fincalc.domain.metric.impl.TotalMetrics;
import io.github.cuukenn.fincalc.domain.metric.impl.XirrMetric;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of the §9.5 financial metrics, built on the shared {@link ExtensionRegistry}: adding a
 * metric means adding a class and registering it, never changing {@link Schedule} or the DTO.
 *
 * <p><b>Order tiers.</b> {@link io.github.cuukenn.fincalc.common.spi.OrderedExtension#order()} is a dependency
 * contract, not cosmetics:
 * <ul>
 *   <li>{@code 10} — derived straight from the schedule and element (the six totals, {@code RENTAL_RATE},
 *       {@code AVG_OUTSTANDING_BALANCE}, and the R103 borrower total {@code BORROWER_TOTAL_REPAYMENT});</li>
 *   <li>{@code 20} — the yield solvers ({@code IRR_PERIOD}, {@code IRR_ANNUAL},
 *       {@code IRR_ANNUAL_SIMPLE}, {@code MIRR}, {@code XIRR}, {@code PAYBACK_PERIOD}) plus the R103
 *       borrower root {@code BORROWER_IRR_PERIOD}, which reads {@code IRR_PERIOD} when the run has
 *       recorded it instead of solving the mirrored set again;</li>
 *   <li>{@code 30} — the metrics that consume a yield ({@code NPV}, {@code XNPV},
 *       {@code DISCOUNTED_PAYBACK}, the durations, {@code CONVEXITY}, {@code TRUE_ANNUAL_RATE}, and
 *       the R103 borrower annualizations {@code BORROWER_IRR_ANNUAL} /
 *       {@code BORROWER_IRR_ANNUAL_SIMPLE}).</li>
 * </ul>
 * A tier-30 metric reads its yield through {@link MetricContext#computed(String)} instead of solving
 * again, so the tier order is what makes the run cost <b>one root solve per yield family</b> rather
 * than one per metric: {@code IRR_PERIOD} pays for the §9.5 family, and the R103 borrower root reads
 * that recorded value instead of adding a solve of its own (R125), so the borrower family costs
 * nothing when {@code IRR_PERIOD} is present. Ties inside a tier keep registration order, which
 * {@link #defaults()} relies on: {@code IRR_PERIOD} is registered before its annualizations and
 * before {@code BORROWER_IRR_PERIOD}, {@code XIRR} before {@code TRUE_ANNUAL_RATE}, and
 * {@code BORROWER_IRR_PERIOD} before the two borrower annualizations that read it.
 *
 * <p><b>Perspective (R103).</b> The borrower family reads the lessee side of the same schedule and
 * answers {@code null} on a {@link CashFlowPerspective#LESSOR} run, which is the default — so the run
 * v1 produced is byte-for-byte what it was, with four extra present-but-null codes in the bag. The
 * registry only gates on {@link MetricContext#perspective()}; no code is prefixed per perspective,
 * because the two families coexist in one bag.
 *
 * <p><b>A metric that fails is recorded, not propagated.</b> This is a deliberate deviation from the
 * brief's「单个指标异常只记 WARN 并跳过」: no logger exists in this build (nothing in
 * {@code calc-bom} or any pom provides one, and no main-code class in this repository logs), and
 * adding a logging dependency to {@code calc-domain} is out of scope. The warning is therefore carried
 * in the output the way {@link FinancialMetrics} documents it — the code is written as
 * <em>present-but-null</em>, the marker a later engine turns into the {@code FCS-CALC-3003}
 * {@code ValidationIssue} of §9.5 item 5 — the exception is swallowed, and every other metric is
 * still computed. §9.5's「不阻断计划表输出」holds either way; what is lost is the stack trace, so the
 * code is where a caller must look.
 */
public final class MetricRegistry {

    private final ExtensionRegistry<FinancialMetric> extensions;

    private MetricRegistry() {
        this.extensions = ExtensionRegistry.empty();
    }

    /**
     * A registry with the full built-in set: the 25 codes of {@link MetricCodes#ALL} — the §9.5
     * table plus the R103 borrower family — nothing else.
     *
     * @return a fresh registry; registering a metric with an existing code replaces it
     */
    public static MetricRegistry defaults() {
        MetricRegistry registry = new MetricRegistry();
        for (FinancialMetric metric : TotalMetrics.instances()) {
            registry.register(metric);
        }
        registry.register(new RentalRateMetric());
        registry.register(new AverageBalanceMetric());
        registry.register(new BorrowerTotalRepaymentMetric());
        // R125: IRR_PERIOD first — the borrower root reads it through instead of solving again —
        // then the borrower root, whose two order-30 annualizations read BORROWER_IRR_PERIOD.
        for (FinancialMetric metric : IrrMetrics.instances()) {
            registry.register(metric);
        }
        registry.register(new BorrowerIrrPeriodMetric());
        for (FinancialMetric metric : BorrowerIrrMetrics.instances()) {
            registry.register(metric);
        }
        registry.register(new MirrMetric());
        for (FinancialMetric metric : XirrMetric.instances()) {
            registry.register(metric);
        }
        for (FinancialMetric metric : PaybackMetric.instances()) {
            registry.register(metric);
        }
        for (FinancialMetric metric : NpvMetric.instances()) {
            registry.register(metric);
        }
        for (FinancialMetric metric : DurationMetrics.instances()) {
            registry.register(metric);
        }
        return registry;
    }

    /**
     * An empty registry for a caller that assembles its own metric set.
     *
     * @return a fresh, empty registry
     */
    public static MetricRegistry empty() {
        return new MetricRegistry();
    }

    /**
     * Registers a metric, replacing one already registered under the same code.
     *
     * @param metric metric to register; never {@code null}
     * @return this registry, for chaining
     */
    public MetricRegistry register(FinancialMetric metric) {
        extensions.register(metric);
        return this;
    }

    /** Registered metrics in {@code order()} sequence, ties in registration order. */
    public List<FinancialMetric> metrics() {
        return extensions.all();
    }

    /** The metric registered under {@link FinancialMetric#code()}, or an empty optional. */
    public Optional<FinancialMetric> find(String code) {
        return extensions.find(code);
    }

    /**
     * Runs every registered metric over {@code context} and collects the results in
     * {@code order()} sequence.
     *
     * <p>A single {@link LinkedHashMap} carries the run: it is installed into a per-run copy of
     * {@code context} (one copy, shared by all metrics) so that a tier-20 or tier-30 metric can read
     * what an earlier metric produced through {@link MetricContext#computed(String)}, and it becomes
     * the bag's insertion order. A metric that throws contributes a present-but-null entry — see the
     * class javadoc — and the run continues.
     *
     * @param context the run's schedules, flows and rates; never {@code null}
     * @return every registered code, in {@code order()} sequence; uncomputable ones present-but-null
     */
    public FinancialMetrics computeAll(MetricContext context) {
        return computeAll(context, null);
    }

    /**
     * {@link #computeAll(MetricContext)} with a diagnostics channel (Task 29 engine wiring).
     *
     * <p>The single-argument overload swallows a throwing metric into a present-but-null entry with
     * no record of <em>which</em> metric failed — the Task 21 gap the engine owns the emission for.
     * This overload appends the {@link FinancialMetric#code()} of every metric whose
     * {@code compute} threw to {@code failedCodes} (a metric that merely returns {@code null}
     * because its inputs are missing is <em>not</em> a failure and is not reported). The engine
     * turns the collected codes into WARN {@code ValidationIssue}s on the schedule; existing
     * callers pass {@code null} (or keep using the one-argument overload) and see no change.
     *
     * @param context     the run's schedules, flows and rates; never {@code null}
     * @param failedCodes mutable list the codes of thrown metrics are appended to, in
     *                    {@code order()} sequence; may be {@code null} for no diagnostics
     * @return every registered code, in {@code order()} sequence; uncomputable ones present-but-null
     */
    public FinancialMetrics computeAll(MetricContext context, List<String> failedCodes) {
        Objects.requireNonNull(context, "context");
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        MetricContext run = context.withRunValues(values);
        for (FinancialMetric metric : extensions.all()) {
            values.put(metric.code(), valueOf(metric, run, failedCodes));
        }
        return FinancialMetrics.of(values);
    }

    /**
     * One metric's value, or {@code null} when it fails; the failure never reaches the caller,
     * but when {@code failedCodes} is non-null the failed metric's code is recorded there.
     */
    private static BigDecimal valueOf(FinancialMetric metric, MetricContext context,
            List<String> failedCodes) {
        try {
            return metric.compute(context);
        } catch (RuntimeException ex) {
            if (failedCodes != null) {
                failedCodes.add(metric.code());
            }
            return null;
        }
    }
}
