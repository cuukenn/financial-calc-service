package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The actual-date yield pair of §9.5: {@code XIRR} (order 20) solves the day-discounted flow set,
 * {@code TRUE_ANNUAL_RATE} (order 30) publishes it as the real annual rate.
 *
 * <p>They live in one class because §9.5's {@code TRUE_ANNUAL_RATE} defaults to {@code XIRR} — a
 * second root find would double the cost of a metric that adds no information. The derived code
 * reads the run's {@code XIRR} through {@link MetricContext#computed(String)} and returns
 * {@code null} when the solve produced nothing.
 *
 * <p><b>Deferred.</b> §9.5 also offers「可配置为单利」, a simple-interest variant of the true annual
 * rate. {@link MetricContext} has a fixed seven-argument shape with no knob for it, so v1 publishes
 * the compound XIRR and the variant waits for a rate-convention option on the context.
 *
 * <p><b>Scale.</b> §9.5 pins {@code IRR_PERIOD} at scale 10 and is silent about {@code XIRR}. Both
 * are roots found to the solver's {@code 1e-10} tolerance, so {@code XIRR} gets the same scale:
 * publishing a rate finer than the solver resolved is false precision. The annual rates that are
 * exact transformations of a root ({@code IRR_ANNUAL}, {@code IRR_ANNUAL_SIMPLE}, {@code MIRR}) stay
 * at {@link io.github.cuukenn.fincalc.common.money.MoneyScale#CALC}. {@code TRUE_ANNUAL_RATE} is a copy of the
 * run's value and is deliberately not re-rounded.
 */
public final class XirrMetric implements FinancialMetric {

    /** The solve runs at the yield tier; the copy of it consumes a yield. */
    private static final int SOLVER_ORDER = 20;
    private static final int DERIVED_ORDER = 30;

    private final String code;
    private final boolean solver;

    private XirrMetric(String code, boolean solver) {
        this.code = code;
        this.solver = solver;
    }

    /**
     * The two codes: the solve first ({@code XIRR}, order 20), then its copy
     * ({@code TRUE_ANNUAL_RATE}, order 30).
     *
     * @return two metrics; unmodifiable
     */
    public static List<FinancialMetric> instances() {
        return Collections.unmodifiableList(Arrays.asList(
                new XirrMetric(MetricCodes.XIRR, true),
                new XirrMetric(MetricCodes.TRUE_ANNUAL_RATE, false)));
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int order() {
        return solver ? SOLVER_ORDER : DERIVED_ORDER;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        if (!solver) {
            return context.computed(MetricCodes.XIRR);
        }
        List<CashFlow> flows = MetricSupport.flowsFor(context);
        if (flows.isEmpty()) {
            return null;
        }
        return MoneyUtils.mid(IrrCalculator.xirr(flows));
    }
}
