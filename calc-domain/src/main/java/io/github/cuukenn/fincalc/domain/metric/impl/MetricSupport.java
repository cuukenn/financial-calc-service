package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Arithmetic shared by the built-in metrics: the signed view of a flow, the side of the
 * transaction the flows are read from, the inputs a context may be missing, and the one rate lookup
 * that must not solve for a yield a second time.
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
     * The flow set to measure, read from the side of the transaction the caller asked for (R103):
     * {@link MetricContext#cashFlows()} as built for {@link CashFlowPerspective#LESSOR}, each flow
     * {@link #flip(CashFlow) flipped} for {@link CashFlowPerspective#LESSEE}.
     *
     * <p>This is the <b>single</b> place that knows the view, so no metric re-decides what a
     * perspective means and a new flow-driven metric cannot forget to ask. The flip keeps every
     * amount positive () and moves the sign into
     * {@link CashFlow#direction()}, so {@link #signed(CashFlow)} negates by construction and the
     * same {@link io.github.cuukenn.fincalc.domain.support.IrrCalculator} runs on either side.
     */
    static List<CashFlow> flowsFor(MetricContext context) {
        List<CashFlow> flows = context.cashFlows();
        if (context.perspective() == CashFlowPerspective.LESSOR) {
            return flows;
        }
        List<CashFlow> flipped = new ArrayList<>(flows.size());
        for (CashFlow flow : flows) {
            flipped.add(flip(flow));
        }
        return Collections.unmodifiableList(flipped);
    }

    /**
     * The same event seen from the other side: same {@code sequence}, {@code date}, {@code type}
     * and <b>same non-negative amount</b>, opposite {@link CashFlowDirection}.
     *
     * <p>A mirrored view is expressed by the direction field, never by a negative amount: a
     * negated amount would be negated a second time by {@link #signed(CashFlow)} and silently
     * invert the whole yield (design document §9.4 review focus 6).
     */
    static CashFlow flip(CashFlow flow) {
        return new CashFlow(flow.sequence(), flow.date(), flow.amount(), flow.type(),
                flow.direction() == CashFlowDirection.INFLOW
                        ? CashFlowDirection.OUTFLOW
                        : CashFlowDirection.INFLOW);
    }

    /**
     * INFLOW positive, OUTFLOW negative — the convention {@link
     * io.github.cuukenn.fincalc.domain.support.IrrCalculator} uses, so a metric never disagrees with the yields
     * about which side of the lease a flow belongs to. A flow taken from
     * {@link #flowsFor(MetricContext)} on the lessee side contributes the negated amount, which is
     * exactly the mirror image of the lessor run.
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

    /**
     * Whether the run's schedule has an annual compounding convention at all. An {@code IRREGULAR}
     * schedule supplies {@code periodsPerYear == 0}, and every metric that annualizes or converts
     * periods to years returns {@code null} instead of a number whose unit is undefined.
     */
    static boolean annualizable(MetricContext context) {
        return context.periodsPerYear() > 0;
    }

    /** {@code periodsPerYear} as a {@link BigDecimal}; only call when {@link #annualizable}. */
    static BigDecimal periodsPerYear(MetricContext context) {
        return BigDecimal.valueOf(context.periodsPerYear());
    }
}
