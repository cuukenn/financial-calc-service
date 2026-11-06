package com.fincalc.domain.metric;

import com.fincalc.common.spi.OrderedExtension;
import java.math.BigDecimal;

/**
 * One financial metric of design document §9.5, registered through {@link MetricRegistry}.
 *
 * <p>A metric is a read-only function of a {@link MetricContext}: it must not mutate the schedule,
 * the element or the context, and it returns {@code null} when the metric is not computable for
 * the inputs it was given. A missing rate, a schedule without rows or a flow set with no sign
 * change is a legitimate "cannot compute" answer, never {@code BigDecimal.ZERO}: §9.5 forbids
 * silently returning 0, and {@link com.fincalc.domain.model.Schedule} carries the resulting
 * present-but-null entry for a later engine to turn into a {@code ValidationIssue}.
 *
 * <p>{@link com.fincalc.common.spi.OrderedExtension#order()} places the metric in one of the three
 * tiers {@link MetricRegistry} runs through: 10 for values derived straight from the schedule,
 * 20 for the yield solvers, 30 for the metrics that consume a yield. A metric of tier 30 reads its
 * dependency through {@link MetricContext#computed(String)} and must never solve for it again.
 *
 * <p>Each metric rounds its own output to the §9.5 scale; no one rounds on their behalf, and
 * rates that already arrive at {@code MoneyScale.CALC} from
 * {@link com.fincalc.domain.support.IrrCalculator} are passed through unrounded.
 */
public interface FinancialMetric extends OrderedExtension {

    /**
     * Computes this metric for {@code context}.
     *
     * @param context the schedule, cash flows and rates to measure; never {@code null}
     * @return the metric value, or {@code null} when it cannot be computed
     */
    BigDecimal compute(MetricContext context);
}
