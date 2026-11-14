package io.github.cuukenn.fincalc.domain.metric;

import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.support.NumericConfig;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the §9.5 metrics read: the schedule, its cash flows, the lease element, the period
 * frequency, the three configurable rates and the perspective whose side of the transaction they
 * are read from.
 *
 * <p><b>Rate units — this is where a silent bug hides.</b> {@link #discountRate()} is a
 * <b>per-period</b> rate, the same unit as {@link MetricCodes#IRR_PERIOD}, because
 * {@link io.github.cuukenn.fincalc.domain.support.IrrCalculator#npv} discounts a flow by its
 * {@link CashFlow#sequence()}. {@link #reinvestRate()} and {@link #financeRate()} are
 * <b>annual</b>: {@link io.github.cuukenn.fincalc.domain.support.IrrCalculator#mirr} divides them by
 * {@link #periodsPerYear()} itself. A caller that mixes the two gets a plausible-looking number
 * that is wrong by a factor of {@code periodsPerYear}.
 *
 * <p><b>Null tolerance.</b> {@code cashFlows} is required; {@code schedule} and {@code element}
 * may be {@code null} for a caller that only wants the flow-driven metrics, and every metric that
 * needs the missing input returns {@code null} instead of failing. {@code discountRate},
 * {@code reinvestRate} and {@code financeRate} being {@code null} means "not configured", which
 * the metrics resolve from the run itself (see each metric's javadoc). A {@code null}
 * {@code perspective} is normalised to {@link CashFlowPerspective#LESSOR}, so no metric ever reads a
 * missing view.
 *
 * <p><b>Yields already produced by the run.</b> A metric that consumes a yield reads it through
 * {@link #computed(String)} rather than solving for it again — one root solve is roughly a hundred
 * NPV evaluations, and repeating it per metric on a long schedule is the wasted work §9.5 forbids.
 * A context built directly through this constructor has no run behind it, so {@link
 * #computed(String)} answers {@code null} for every code; only the per-run copy
 * {@link MetricRegistry} installs during {@link MetricRegistry#computeAll} carries values, and
 * those follow the {@link io.github.cuukenn.fincalc.common.spi.OrderedExtension#order()} sequence.
 *
 * <p><b>Perspective (R103).</b> {@code cashFlows()} always carries the flows as the caller built
 * them — the lessor event table — and {@link CashFlowPerspective#LESSEE} is applied by
 * {@code MetricSupport.flowsFor(context)}, the single place that knows the view. The perspective
 * changes <em>no</em> key: {@link #computed(String)} and {@code FinancialMetrics} stay keyed by the
 * plain codes of {@link MetricCodes}, because the two views coexist in one bag and the registry
 * gates each family on {@link #perspective()} instead of on a code prefix.
 */
public final class MetricContext {

    private final List<CashFlow> cashFlows;
    private final Schedule schedule;
    private final LeaseElement element;
    private final int periodsPerYear;
    private final BigDecimal discountRate;
    private final BigDecimal reinvestRate;
    private final BigDecimal financeRate;
    private final CashFlowPerspective perspective;
    private final Map<String, BigDecimal> computedValues;
    private final PeriodicRootHandoff rootHandoff;
    private final NumericConfig numericConfig;

    /**
     * A lessor-view context — the v1 shape; delegates with {@link CashFlowPerspective#LESSOR}.
     *
     * @param cashFlows     flows to measure, ordered by {@link CashFlow#sequence()}; never
     *                      {@code null}
     * @param schedule      schedule the totals and balances read; may be {@code null}
     * @param element       lease element the rates and residual read; may be {@code null}
     * @param periodsPerYear periods per year for annualization and year-denominated metrics; at
     *                      least 1, or {@code 0} for an {@code IRREGULAR} schedule (no annual view
     *                      exists — the annualizing metrics return {@code null})
     * @param discountRate  per-period discount rate; {@code null} means derive it from the run
     * @param reinvestRate  annual reinvestment rate for MIRR; {@code null} means default it
     * @param financeRate   annual finance rate for MIRR; {@code null} means default it
     */
    public MetricContext(List<CashFlow> cashFlows, Schedule schedule, LeaseElement element,
            int periodsPerYear, BigDecimal discountRate, BigDecimal reinvestRate,
            BigDecimal financeRate) {
        this(cashFlows, schedule, element, periodsPerYear, discountRate, reinvestRate, financeRate,
                CashFlowPerspective.LESSOR);
    }

    /**
     * A context that reads the metrics from {@code perspective}'s side of the transaction.
     *
     * @param cashFlows     flows as the caller built them (the lessor event table); never
     *                      {@code null}
     * @param schedule      schedule the totals and balances read; may be {@code null}
     * @param element       lease element the rates and residual read; may be {@code null}
     * @param periodsPerYear periods per year; at least 1, or {@code 0} for an {@code IRREGULAR}
     *                      schedule
     * @param discountRate  per-period discount rate; {@code null} means derive it from the run
     * @param reinvestRate  annual reinvestment rate for MIRR; {@code null} means default it
     * @param financeRate   annual finance rate for MIRR; {@code null} means default it
     * @param perspective   whose side to read; {@code null} means
     *                      {@link CashFlowPerspective#LESSOR}
     */
    public MetricContext(List<CashFlow> cashFlows, Schedule schedule, LeaseElement element,
            int periodsPerYear, BigDecimal discountRate, BigDecimal reinvestRate,
            BigDecimal financeRate, CashFlowPerspective perspective) {
        this(cashFlows, schedule, element, periodsPerYear, discountRate, reinvestRate, financeRate,
                perspective, Collections.<String, BigDecimal>emptyMap(),
                new PeriodicRootHandoff(), NumericConfig.defaults());
    }

    /** 经 {@code NumericConfig} 提供求根参数的重载（逼近次数/容差/区间）；null 取默认。 */
    public MetricContext(List<CashFlow> cashFlows, Schedule schedule, LeaseElement element,
            int periodsPerYear, BigDecimal discountRate, BigDecimal reinvestRate,
            BigDecimal financeRate, CashFlowPerspective perspective, NumericConfig numericConfig) {
        this(cashFlows, schedule, element, periodsPerYear, discountRate, reinvestRate, financeRate,
                perspective, Collections.<String, BigDecimal>emptyMap(),
                new PeriodicRootHandoff(), numericConfig);
    }

    private MetricContext(List<CashFlow> cashFlows, Schedule schedule, LeaseElement element,
            int periodsPerYear, BigDecimal discountRate, BigDecimal reinvestRate,
            BigDecimal financeRate, CashFlowPerspective perspective,
            Map<String, BigDecimal> computedValues) {
        this(cashFlows, schedule, element, periodsPerYear, discountRate, reinvestRate, financeRate,
                perspective, computedValues, new PeriodicRootHandoff());
    }

    private MetricContext(List<CashFlow> cashFlows, Schedule schedule, LeaseElement element,
            int periodsPerYear, BigDecimal discountRate, BigDecimal reinvestRate,
            BigDecimal financeRate, CashFlowPerspective perspective,
            Map<String, BigDecimal> computedValues, PeriodicRootHandoff rootHandoff) {
        this(cashFlows, schedule, element, periodsPerYear, discountRate, reinvestRate, financeRate,
                perspective, computedValues, rootHandoff, null);
    }

    private MetricContext(List<CashFlow> cashFlows, Schedule schedule, LeaseElement element,
            int periodsPerYear, BigDecimal discountRate, BigDecimal reinvestRate,
            BigDecimal financeRate, CashFlowPerspective perspective,
            Map<String, BigDecimal> computedValues, PeriodicRootHandoff rootHandoff,
            NumericConfig numericConfig) {
        Objects.requireNonNull(cashFlows, "cashFlows");
        if (periodsPerYear < 0) {
            throw new IllegalArgumentException("periodsPerYear must be at least 0: "
                    + periodsPerYear);
        }
        this.cashFlows = Collections.unmodifiableList(new ArrayList<>(cashFlows));
        this.schedule = schedule;
        this.element = element;
        this.periodsPerYear = periodsPerYear;
        this.discountRate = discountRate;
        this.reinvestRate = reinvestRate;
        this.financeRate = financeRate;
        this.perspective = perspective == null ? CashFlowPerspective.LESSOR : perspective;
        this.computedValues = computedValues;
        this.rootHandoff = Objects.requireNonNull(rootHandoff, "rootHandoff");
        this.numericConfig = numericConfig == null ? NumericConfig.defaults() : numericConfig;
    }

    /**
     * Copy of this context that reads {@link #computed(String)} from {@code runValues}, the map
     * {@link MetricRegistry} fills as it walks the registered metrics in
     * {@link io.github.cuukenn.fincalc.common.spi.OrderedExtension#order()} sequence. One copy per run, not per
     * metric, so every metric sees the same accumulated results.
     *
     * <p>The copy carries {@link #perspective()} unchanged: the tier-20/30 metrics of the run must
     * read the same side of the transaction the caller asked for, and dropping it here would make
     * the whole run silently answer as a lessor.
     *
     * <p>Package-private on purpose: only the registry assembles a run, and a metric is never
     * allowed to install its own dependencies.
     */
    MetricContext withRunValues(Map<String, BigDecimal> runValues) {
        return new MetricContext(cashFlows, schedule, element, periodsPerYear, discountRate,
                reinvestRate, financeRate, perspective,
                Objects.requireNonNull(runValues, "runValues"), rootHandoff, numericConfig);
    }

    /** Cash flows to measure, in sequence order; unmodifiable. */
    public List<CashFlow> cashFlows() {
        return cashFlows;
    }

    /** Schedule whose installments the totals, totals-derived ratios and balances read. */
    public Schedule schedule() {
        return schedule;
    }

    /** 数值求解配置（逼近次数/容差/求根区间）；非测算要素，不改任何口径。 */
    public NumericConfig numericConfig() {
        return numericConfig;
    }

    /** Lease element providing principal, nominal rate and residual. */
    public LeaseElement element() {
        return element;
    }

    /**
     * Payment periods per year, the {@code m} of the §9.5 formulas; at least 1, or {@code 0} for
     * an {@code IRREGULAR} schedule where no annual compounding convention exists and the
     * annualizing metrics return {@code null} (a wrong-unit number is worse than a missing one).
     */
    public int periodsPerYear() {
        return periodsPerYear;
    }

    /** Per-period discount rate, or {@code null} when the run must derive it. */
    public BigDecimal discountRate() {
        return discountRate;
    }

    /** Annual reinvestment rate for MIRR, or {@code null} when the run must default it. */
    public BigDecimal reinvestRate() {
        return reinvestRate;
    }

    /** Annual finance rate for MIRR, or {@code null} when the run must default it. */
    public BigDecimal financeRate() {
        return financeRate;
    }

    /**
     * The side of the transaction the run reads its metrics from (R103); never {@code null} —
     * an unnamed perspective is {@link CashFlowPerspective#LESSOR}, the v1 posture.
     */
    public CashFlowPerspective perspective() {
        return perspective;
    }

    /**
     * Value another metric of the same run already produced.
     *
     * <p>{@code null} when the code has not been computed yet, when it was computed and answered
     * {@code null} (not computable), or when this context was built standalone without a run. A
     * consumer metric must treat all three the same way: it cannot compute, so it returns
     * {@code null}.
     *
     * @param code metric code, e.g. {@link MetricCodes#IRR_PERIOD}
     * @return the value already produced by an earlier metric of this run, or {@code null}
     */
    public BigDecimal computed(String code) {
        return computedValues.get(code);
    }

    /**
     * The channel the run writes its unrounded periodic root onto, for a consumer that needs the root
     * itself rather than its §9.5 rounded publication — see {@link PeriodicRootHandoff} for when the
     * value may be reused and for what it is not.
     *
     * <p>The same object is shared by this context and every {@code withRunValues} copy of it, so the
     * caller that built the context reads what the run recorded. It is the one mutable thing a
     * {@code MetricContext} exposes, and it is not a §9.5 output: no metric code reads it.
     *
     * @return this run's hand-off; never {@code null}
     */
    public PeriodicRootHandoff rootHandoff() {
        return rootHandoff;
    }
}
