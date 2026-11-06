package com.fincalc.domain.metric;

import com.fincalc.common.spi.ExtensionRegistry;
import com.fincalc.domain.metric.impl.AverageBalanceMetric;
import com.fincalc.domain.metric.impl.DurationMetrics;
import com.fincalc.domain.metric.impl.IrrMetrics;
import com.fincalc.domain.metric.impl.MirrMetric;
import com.fincalc.domain.metric.impl.NpvMetric;
import com.fincalc.domain.metric.impl.PaybackMetric;
import com.fincalc.domain.metric.impl.RentalRateMetric;
import com.fincalc.domain.metric.impl.TotalMetrics;
import com.fincalc.domain.metric.impl.XirrMetric;
import com.fincalc.domain.model.Schedule;
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
 * <p><b>Order tiers.</b> {@link com.fincalc.common.spi.OrderedExtension#order()} is a dependency
 * contract, not cosmetics:
 * <ul>
 *   <li>{@code 10} — derived straight from the schedule and element (the six totals, {@code RENTAL_RATE},
 *       {@code AVG_OUTSTANDING_BALANCE});</li>
 *   <li>{@code 20} — the yield solvers ({@code IRR_PERIOD}, {@code IRR_ANNUAL},
 *       {@code IRR_ANNUAL_SIMPLE}, {@code MIRR}, {@code XIRR}, {@code PAYBACK_PERIOD});</li>
 *   <li>{@code 30} — the metrics that consume a yield ({@code NPV}, {@code XNPV},
 *       {@code DISCOUNTED_PAYBACK}, the durations, {@code CONVEXITY}, {@code TRUE_ANNUAL_RATE}).</li>
 * </ul>
 * A tier-30 metric reads its yield through {@link MetricContext#computed(String)} instead of solving
 * again, so the tier order is what makes the run cost <b>one</b> root solve per schedule rather than
 * one per metric. Ties inside a tier keep registration order, which {@link #defaults()} relies on:
 * {@code IRR_PERIOD} is registered before its annualizations, and {@code XIRR} before
 * {@code TRUE_ANNUAL_RATE}.
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
     * A registry with the full §9.5 built-in set: the 21 codes of {@link MetricCodes#ALL}, nothing
     * else.
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
        for (FinancialMetric metric : IrrMetrics.instances()) {
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
        Objects.requireNonNull(context, "context");
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        MetricContext run = context.withRunValues(values);
        for (FinancialMetric metric : extensions.all()) {
            values.put(metric.code(), valueOf(metric, run));
        }
        return FinancialMetrics.of(values);
    }

    /** One metric's value, or {@code null} when it fails; the failure never reaches the caller. */
    private static BigDecimal valueOf(FinancialMetric metric, MetricContext context) {
        try {
            return metric.compute(context);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
