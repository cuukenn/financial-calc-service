package com.fincalc.domain.metric;

import com.fincalc.domain.model.CashFlow;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.Schedule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the §9.5 metrics read: the schedule, its cash flows, the lease element, the period
 * frequency and the three configurable rates.
 *
 * <p><b>Rate units — this is where a silent bug hides.</b> {@link #discountRate()} is a
 * <b>per-period</b> rate, the same unit as {@link MetricCodes#IRR_PERIOD}, because
 * {@link com.fincalc.domain.support.IrrCalculator#npv} discounts a flow by its
 * {@link CashFlow#sequence()}. {@link #reinvestRate()} and {@link #financeRate()} are
 * <b>annual</b>: {@link com.fincalc.domain.support.IrrCalculator#mirr} divides them by
 * {@link #periodsPerYear()} itself. A caller that mixes the two gets a plausible-looking number
 * that is wrong by a factor of {@code periodsPerYear}.
 *
 * <p><b>Null tolerance.</b> {@code cashFlows} is required; {@code schedule} and {@code element}
 * may be {@code null} for a caller that only wants the flow-driven metrics, and every metric that
 * needs the missing input returns {@code null} instead of failing. {@code discountRate},
 * {@code reinvestRate} and {@code financeRate} being {@code null} means "not configured", which
 * the metrics resolve from the run itself (see each metric's javadoc).
 *
 * <p><b>Yields already produced by the run.</b> A metric that consumes a yield reads it through
 * {@link #computed(String)} rather than solving for it again — one root solve is roughly a hundred
 * NPV evaluations, and repeating it per metric on a long schedule is the wasted work §9.5 forbids.
 * A context built directly through this constructor has no run behind it, so {@link
 * #computed(String)} answers {@code null} for every code; only the per-run copy
 * {@link MetricRegistry} installs during {@link MetricRegistry#computeAll} carries values, and
 * those follow the {@link com.fincalc.common.spi.OrderedExtension#order()} sequence.
 */
public final class MetricContext {

    private final List<CashFlow> cashFlows;
    private final Schedule schedule;
    private final LeaseElement element;
    private final int periodsPerYear;
    private final BigDecimal discountRate;
    private final BigDecimal reinvestRate;
    private final BigDecimal financeRate;
    private final Map<String, BigDecimal> computedValues;

    /**
     * @param cashFlows     flows to measure, ordered by {@link CashFlow#sequence()}; never
     *                      {@code null}
     * @param schedule      schedule the totals and balances read; may be {@code null}
     * @param element       lease element the rates and residual read; may be {@code null}
     * @param periodsPerYear periods per year for annualization and year-denominated metrics; at
     *                      least 1 (an {@code IRREGULAR} schedule supplies its own effective value)
     * @param discountRate  per-period discount rate; {@code null} means derive it from the run
     * @param reinvestRate  annual reinvestment rate for MIRR; {@code null} means default it
     * @param financeRate   annual finance rate for MIRR; {@code null} means default it
     */
    public MetricContext(List<CashFlow> cashFlows, Schedule schedule, LeaseElement element,
            int periodsPerYear, BigDecimal discountRate, BigDecimal reinvestRate,
            BigDecimal financeRate) {
        this(cashFlows, schedule, element, periodsPerYear, discountRate, reinvestRate, financeRate,
                Collections.<String, BigDecimal>emptyMap());
    }

    private MetricContext(List<CashFlow> cashFlows, Schedule schedule, LeaseElement element,
            int periodsPerYear, BigDecimal discountRate, BigDecimal reinvestRate,
            BigDecimal financeRate, Map<String, BigDecimal> computedValues) {
        Objects.requireNonNull(cashFlows, "cashFlows");
        if (periodsPerYear < 1) {
            throw new IllegalArgumentException("periodsPerYear must be at least 1: "
                    + periodsPerYear);
        }
        this.cashFlows = Collections.unmodifiableList(new ArrayList<>(cashFlows));
        this.schedule = schedule;
        this.element = element;
        this.periodsPerYear = periodsPerYear;
        this.discountRate = discountRate;
        this.reinvestRate = reinvestRate;
        this.financeRate = financeRate;
        this.computedValues = computedValues;
    }

    /**
     * Copy of this context that reads {@link #computed(String)} from {@code runValues}, the map
     * {@link MetricRegistry} fills as it walks the registered metrics in
     * {@link com.fincalc.common.spi.OrderedExtension#order()} sequence. One copy per run, not per
     * metric, so every metric sees the same accumulated results.
     *
     * <p>Package-private on purpose: only the registry assembles a run, and a metric is never
     * allowed to install its own dependencies.
     */
    MetricContext withRunValues(Map<String, BigDecimal> runValues) {
        return new MetricContext(cashFlows, schedule, element, periodsPerYear, discountRate,
                reinvestRate, financeRate,
                Objects.requireNonNull(runValues, "runValues"));
    }

    /** Cash flows to measure, in sequence order; unmodifiable. */
    public List<CashFlow> cashFlows() {
        return cashFlows;
    }

    /** Schedule whose installments the totals, totals-derived ratios and balances read. */
    public Schedule schedule() {
        return schedule;
    }

    /** Lease element providing principal, nominal rate and residual. */
    public LeaseElement element() {
        return element;
    }

    /** Payment periods per year, the {@code m} of the §9.5 formulas; at least 1. */
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
}
