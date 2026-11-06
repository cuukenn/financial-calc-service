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
 * The sensitivity block of §9.5: {@code DURATION_MACAULAY}, {@code DURATION_MODIFIED} and
 * {@code CONVEXITY}, all at order 30 and scale 10, all consuming the run's yield rather than
 * solving for one.
 *
 * <p><b>The convention, because the brief's own line would break it.</b> The brief writes
 * {@code PV_t = CF_t / (1+y/m)^t} with {@code t = periodNo/m}. Applied literally that discounts a
 * 5-year monthly schedule by {@code 1.005^5} instead of {@code 1.005^60} — five periods instead of
 * sixty — and it contradicts §9.5's own {@code DURATION_MODIFIED = D / (1 + y/m)}, which only makes
 * sense if the discount factor is the per-period one. So these metrics keep the coordinates
 * {@link com.fincalc.domain.support.IrrCalculator#npv} uses: {@code t = flow.sequence()} periods,
 * factor {@code (1 + periodRate)}. The year conversion happens once, in the numerator
 * {@code (t / m)}, which is what makes §9.5's unit「年」true; {@code m^2} does the same for
 * 单位：年².
 *
 * <p><b>Weights are magnitudes.</b> A yield is by definition the rate at which the signed present
 * values sum to zero, so weighting signed flows at the run's {@code IRR_PERIOD} would divide
 * {@code Σ(t·PV_t)} by {@code 0} and make every duration uncomputable. Duration measures the average
 * timing of the schedule's money movements, so each weight is {@code |CF_t| / (1 + rate)^t}. That
 * also makes the three metrics independent of the cash flow perspective: mirroring every direction
 * (lessor to lessee, §9.4) leaves them unchanged.
 *
 * <p>The zero-denominator guard §9.5 asks for stays: a flow set whose present values contribute
 * nothing has no weighted average to take, and answers {@code null}.
 */
public final class DurationMetrics implements FinancialMetric {

    private static final int ORDER = 30;

    /** Which of the three §9.5 sensitivity codes this instance publishes. */
    private enum Variant {
        MACAULAY, MODIFIED, CONVEXITY
    }

    private final String code;
    private final Variant variant;

    private DurationMetrics(String code, Variant variant) {
        this.code = code;
        this.variant = variant;
    }

    /**
     * The three sensitivity metrics, all at order 30.
     *
     * @return three metrics; unmodifiable
     */
    public static List<FinancialMetric> instances() {
        return Collections.unmodifiableList(Arrays.asList(
                new DurationMetrics(MetricCodes.DURATION_MACAULAY, Variant.MACAULAY),
                new DurationMetrics(MetricCodes.DURATION_MODIFIED, Variant.MODIFIED),
                new DurationMetrics(MetricCodes.CONVEXITY, Variant.CONVEXITY)));
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        if (context.cashFlows().isEmpty()) {
            return null;
        }
        BigDecimal rate = MetricSupport.periodRate(context);
        if (rate == null) {
            return null;
        }
        BigDecimal growth = BigDecimal.ONE.add(rate);
        if (growth.signum() <= 0) {
            return null;
        }

        BigDecimal presentValues = BigDecimal.ZERO;
        BigDecimal timeWeighted = BigDecimal.ZERO;
        BigDecimal convexWeighted = BigDecimal.ZERO;
        for (CashFlow flow : context.cashFlows()) {
            BigDecimal periods = BigDecimal.valueOf(flow.sequence());
            BigDecimal present = flow.amount().divide(growth.pow(flow.sequence(), MetricSupport.MC),
                    MetricSupport.MC);
            presentValues = presentValues.add(present, MetricSupport.MC);
            timeWeighted = timeWeighted.add(periods.multiply(present, MetricSupport.MC),
                    MetricSupport.MC);
            convexWeighted = convexWeighted.add(
                    periods.multiply(periods.add(BigDecimal.ONE), MetricSupport.MC)
                            .multiply(present, MetricSupport.MC), MetricSupport.MC);
        }
        BigDecimal durationPeriods = MetricSupport.quotient(timeWeighted, presentValues);
        if (durationPeriods == null) {
            return null;
        }
        BigDecimal years = MetricSupport.periodsPerYear(context);
        BigDecimal macaulayYears = MetricSupport.quotient(durationPeriods, years);

        if (variant == Variant.MACAULAY) {
            return MoneyUtils.mid(macaulayYears);
        }
        if (variant == Variant.MODIFIED) {
            return MoneyUtils.mid(MetricSupport.quotient(macaulayYears, growth));
        }
        BigDecimal denominator = growth.pow(2, MetricSupport.MC)
                .multiply(presentValues, MetricSupport.MC)
                .multiply(years.pow(2), MetricSupport.MC);
        return MoneyUtils.mid(MetricSupport.quotient(convexWeighted, denominator));
    }
}
