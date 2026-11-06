package com.fincalc.domain.metric.impl;

import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.metric.FinancialMetric;
import com.fincalc.domain.metric.MetricCodes;
import com.fincalc.domain.metric.MetricContext;
import com.fincalc.domain.model.CashFlow;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The recovery periods of §9.5: {@code PAYBACK_PERIOD} (order 20) walks the raw flows,
 * {@code DISCOUNTED_PAYBACK} (order 30) walks them discounted at the run's rate. Both report
 * <b>years</b> at scale 10.
 *
 * <p>Interpolation follows §9.5: with {@code k} the first index whose running total reaches zero,
 * the money still missing at {@code k − 1} is recovered by a fraction of flow {@code k}, so
 *
 * <pre>
 * periods = (k − 1) + |cumulative(k − 1)| / CF(k)
 * years   = periods / periodsPerYear
 * </pre>
 *
 * <p>A walk only means something if there is something to recover: the running total after the first
 * flow must be negative. A flow set that starts with money coming in (a pure inflow schedule) has no
 * payback and answers {@code null} — not 0, which §9.5 forbids, and not the first index, which would
 * read as "recovered instantly". A walk still negative at the last flow has not recovered within the
 * horizon and is likewise {@code null}.
 *
 * <p>Zero is approached, not required exactly: a schedule is quoted in cents, and at the yield the
 * discounted sum is zero by construction while the {@code IRR_PERIOD} the run published is rounded at
 * ten scales, which leaves a sub-cent residual on either side of the last flow. A running total
 * inside one cent of zero has therefore recovered; a stricter test would report "never recovered" for
 * a schedule that repays exactly.
 *
 * <p>Both walks read the flows in list order, which is the
 * {@link com.fincalc.domain.port.CashFlowBuilder} contract: ordered by date and renumbered
 * {@code sequence} from 0, so the index and the period coordinate are the same thing. The discounted
 * walk discounts by {@link CashFlow#sequence()}, the same coordinate
 * {@link com.fincalc.domain.support.IrrCalculator#npv} uses.
 */
public final class PaybackMetric implements FinancialMetric {

    /** One cent: the money precision a schedule is quoted at, and how close to zero counts as zero. */
    private static final BigDecimal CENT = new BigDecimal("0.01");

    private static final int STATIC_ORDER = 20;
    private static final int DISCOUNTED_ORDER = 30;

    private final String code;
    private final boolean discounted;

    private PaybackMetric(String code, boolean discounted) {
        this.code = code;
        this.discounted = discounted;
    }

    /**
     * The two periods: the static walk (order 20, it needs no rate) and the discounted walk (order
     * 30, because it consumes the yield the run solved).
     *
     * @return two metrics; unmodifiable
     */
    public static List<FinancialMetric> instances() {
        return Collections.unmodifiableList(Arrays.asList(
                new PaybackMetric(MetricCodes.PAYBACK_PERIOD, false),
                new PaybackMetric(MetricCodes.DISCOUNTED_PAYBACK, true)));
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int order() {
        return discounted ? DISCOUNTED_ORDER : STATIC_ORDER;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        List<CashFlow> flows = context.cashFlows();
        if (flows.size() < 2) {
            return null;
        }
        BigDecimal rate = discounted ? MetricSupport.periodRate(context) : null;
        if (discounted && rate == null) {
            // Neither a configured discount rate nor a yield solved by this run: nothing to
            // discount by, so the dynamic payback is not computable rather than equal to the static
            // one.
            return null;
        }

        BigDecimal previous = MetricSupport.discounted(flows.get(0), rate);
        if (previous.signum() >= 0) {
            return null;
        }
        for (int index = 1; index < flows.size(); index++) {
            BigDecimal value = MetricSupport.discounted(flows.get(index), rate);
            BigDecimal cumulative = previous.add(value, MetricSupport.MC);
            if (cumulative.compareTo(CENT.negate()) >= 0) {
                BigDecimal periods = BigDecimal.valueOf(index - 1L).add(leg(previous, value));
                return MoneyUtils.mid(MetricSupport.quotient(periods,
                        MetricSupport.periodsPerYear(context)));
            }
            previous = cumulative;
        }
        return null;
    }

    /**
     * The fractional period the crossing flow takes to cover the outstanding {@code previous}. A
     * zero-sized crossing flow recovers nothing, so the recovery sits at the previous boundary
     * instead of dividing by zero.
     */
    private static BigDecimal leg(BigDecimal previous, BigDecimal crossingFlow) {
        BigDecimal size = crossingFlow.abs();
        return size.signum() == 0 ? BigDecimal.ZERO : previous.abs().divide(size, MetricSupport.MC);
    }
}
