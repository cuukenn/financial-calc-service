package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
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
 * horizon and is likewise {@code null} — but the two walks approach the last flow differently, on
 * purpose.
 *
 * <p><b>Static walk, strict.</b> Its amounts are cent-exact, so a crossing means the running total
 * has reached zero exactly. A schedule that ends one cent short of recovery is「期末未回收」per §9.5
 * and answers {@code null}: no tolerance belongs there.
 *
 * <p><b>Discounted walk, one-cent tolerance.</b> At the yield the discounted sum is zero by
 * construction, but the run publishes {@code IRR_PERIOD} at scale 10 and this walk discounts at that
 * rounded rate, which leaves a residual of about {@code -2.5e-4} on the last flow of a schedule that
 * repays exactly. A strict test would report "never recovered" for such a schedule, so
 * {@link #RECOVERY_TOLERANCE} is what counts as zero there — and only there.
 *
 * <p>Because that tolerance can approve a crossing a hair below zero, the interpolated leg of the
 * <b>final</b> flow is capped at one period: a sub-cent shortfall at maturity means recovery at the
 * horizon ({@code n / periodsPerYear}), never a payback past the schedule it was measured from.
 *
 * <p>Both walks read the flows in list order, which is the
 * {@link io.github.cuukenn.fincalc.domain.port.CashFlowBuilder} contract: ordered by date and renumbered
 * {@code sequence} from 0, so the index and the period coordinate are the same thing. The discounted
 * walk discounts by {@link CashFlow#sequence()}, the same coordinate
 * {@link io.github.cuukenn.fincalc.domain.support.IrrCalculator#npv} uses.
 */
public final class PaybackMetric implements FinancialMetric {

    /**
     * One cent, the money precision a schedule is quoted at. It is the recovery line of the
     * {@link #DISCOUNTED_ORDER discounted} walk only: the run publishes {@code IRR_PERIOD} at scale
     * 10, so discounting the flows at that rounded rate leaves a residual of about {@code -2.5e-4}
     * where the exact yield would leave zero. The static walk recovers on the exact line instead.
     */
    private static final BigDecimal RECOVERY_TOLERANCE = new BigDecimal("0.01");

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
        List<CashFlow> flows = MetricSupport.flowsFor(context);
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
        // The static walk reaches zero exactly; only the discounted one is allowed a cent of slack.
        BigDecimal recoveryLine = discounted ? RECOVERY_TOLERANCE.negate() : BigDecimal.ZERO;
        for (int index = 1; index < flows.size(); index++) {
            BigDecimal value = MetricSupport.discounted(flows.get(index), rate);
            BigDecimal cumulative = previous.add(value, MetricSupport.MC);
            if (cumulative.compareTo(recoveryLine) >= 0) {
                boolean finalFlow = index == flows.size() - 1;
                BigDecimal periods = BigDecimal.valueOf(index - 1L)
                        .add(leg(previous, value, finalFlow));
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
     * instead of dividing by zero. When the crossing is the last flow of the walk the leg is capped
     * at one period: a sub-cent shortfall approved by {@link #RECOVERY_TOLERANCE} at maturity means
     * the money comes back <em>at</em> the horizon, and a payback past the schedule's own term would
     * be a number no lease could produce.
     */
    private static BigDecimal leg(BigDecimal previous, BigDecimal crossingFlow, boolean finalFlow) {
        BigDecimal size = crossingFlow.abs();
        if (size.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal covered = previous.abs().divide(size, MetricSupport.MC);
        return finalFlow && covered.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : covered;
    }
}
