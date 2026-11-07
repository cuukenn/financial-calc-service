package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.List;

/**
 * Arithmetic shared by the built-in metrics: the signed view of a flow, the inputs a context may
 * be missing, and the one rate lookup that must not solve for a yield a second time.
 *
 * <p>Division always goes through {@link MathContext#DECIMAL128} or an explicit scale: an unguarded
 * {@code divide} throws {@link ArithmeticException} on a non-terminating quotient, which in a metric
 * run would show up as a silently missing number.
 */
final class MetricSupport {

    /** Working precision of the metric sums and quotients. */
    static final MathContext MC = MathContext.DECIMAL128;

    private MetricSupport() {
    }

    /** Schedule rows, or an empty list when the context carries no schedule. */
    static List<Installment> rows(MetricContext context) {
        Schedule schedule = context.schedule();
        return schedule == null ? Collections.emptyList() : schedule.installments();
    }

    /**
     * INFLOW positive, OUTFLOW negative — the convention {@link
     * io.github.cuukenn.fincalc.domain.support.IrrCalculator} uses, so a metric never disagrees with the yields
     * about which side of the lease a flow belongs to.
     */
    static BigDecimal signed(CashFlow flow) {
        return flow.direction() == CashFlowDirection.INFLOW ? flow.amount() : flow.amount().negate();
    }

    /** Present value of a flow's signed amount at a per-period rate, discounted by its sequence. */
    static BigDecimal discounted(CashFlow flow, BigDecimal periodRate) {
        if (periodRate == null) {
            return signed(flow);
        }
        return signed(flow).divide(BigDecimal.ONE.add(periodRate).pow(flow.sequence(), MC), MC);
    }

    /**
     * The per-period rate the §9.5 discount metrics use: the configured {@code discountRate}, else
     * the {@code IRR_PERIOD} this run has already solved.
     *
     * <p>Reading it through {@link MetricContext#computed(String)} is the whole point: a root solve
     * costs about a hundred NPV evaluations, so NPV, the discounted payback and the duration group all
     * reuse the one solve {@code IRR_PERIOD} did at order 20 instead of each paying for it again.
     * {@code null} when neither is available, which makes every consumer of it return {@code null}.
     */
    static BigDecimal periodRate(MetricContext context) {
        return context.discountRate() != null
                ? context.discountRate()
                : context.computed(MetricCodes.IRR_PERIOD);
    }

    /** Quotient at working precision, or {@code null} when the denominator is zero. */
    static BigDecimal quotient(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? null : numerator.divide(denominator, MC);
    }

    /** {@code periodsPerYear} as a {@link BigDecimal}; the constructor guarantees it is positive. */
    static BigDecimal periodsPerYear(MetricContext context) {
        return BigDecimal.valueOf(context.periodsPerYear());
    }
}
