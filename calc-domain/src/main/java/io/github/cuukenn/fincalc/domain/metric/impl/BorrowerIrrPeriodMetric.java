package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code BORROWER_IRR_PERIOD} at order 20 (R103/R125): the borrower-view root, published by
 * <em>reading</em> the root the run already solved rather than solving the flipped flow set again.
 *
 * <p><b>Same root, read through.</b> A global sign flip negates the NPV function and leaves its
 * zeros unchanged — {@link CashFlowPerspective}'s own javadoc says so: the lessee view is the exact
 * mirror, and "IRR is insensitive to flipping every sign, NPV changes sign only".
 * {@link IrrCalculator#periodIrr} is sign-symmetric for the same reason (its sign-change test and
 * Newton step both survive a global negation), so solving the lessee set would land on the
 * bit-identical value {@code IRR_PERIOD} already published. This metric therefore reads
 * {@link MetricContext#computed(String) context.computed(IRR_PERIOD)} instead (R125): no second
 * root solve, no extra ~100 NPV evaluations. It falls back to solving
 * {@link MetricSupport#flowsFor(MetricContext)} only when that key is absent or held a null value —
 * a custom registry that registers the borrower family without {@code IRR_PERIOD} keeps working.
 *
 * <p>Registration is the dependency contract: {@code MetricRegistry.defaults()} registers this
 * metric after {@link IrrMetrics}, so the recorded root exists by the time it runs. The two
 * {@code BORROWER_IRR_ANNUAL*} metrics then read this code back through
 * {@link MetricContext#computed(String)}, so the whole borrower family costs at most one solve.
 *
 * <p>The flip itself keeps every amount positive and moves the sign into the {@code direction}
 * field, so the lessee's funded principal is an inflow and the rents are outflows without a
 * negative amount anywhere ().
 *
 * <p>Scale: {@link io.github.cuukenn.fincalc.common.money.MoneyScale#INTERMEDIATE} (10), the §9.5
 * scale for a solved periodic root — a rate finer than the solver's {@code 1e-10} tolerance would
 * be false precision; the recorded {@code IRR_PERIOD} is already at that scale and is passed
 * through. Present-but-null on a {@link CashFlowPerspective#LESSOR} run and when the flow set has
 * no root.
 */
public final class BorrowerIrrPeriodMetric implements FinancialMetric {

    /**
     * The code this metric publishes, and the key {@link BorrowerIrrMetrics} reads it back from —
     * one name for the record, so the writer and the readers cannot drift apart.
     */
    public static final String BORROWER_IRR_PERIOD = MetricCodes.BORROWER_IRR_PERIOD;

    private static final int ORDER = 20;

    @Override
    public String code() {
        return BORROWER_IRR_PERIOD;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        if (context.perspective() != CashFlowPerspective.LESSEE) {
            return null;
        }
        BigDecimal recorded = context.computed(MetricCodes.IRR_PERIOD);
        if (recorded != null) {
            return recorded;
        }
        List<CashFlow> flows = MetricSupport.flowsFor(context);
        if (flows.isEmpty()) {
            return null;
        }
        return MoneyUtils.mid(IrrCalculator.periodIrr(flows, context.numericConfig()));
    }
}
