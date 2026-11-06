package com.fincalc.domain.metric.impl;

import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.metric.FinancialMetric;
import com.fincalc.domain.metric.MetricCodes;
import com.fincalc.domain.metric.MetricContext;
import com.fincalc.domain.support.IrrCalculator;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The present-value pair of §9.5: {@code NPV} discounts by {@link com.fincalc.domain.model.CashFlow
 * #sequence()} at a per-period rate, {@code XNPV} by actual days over 365 at an annual rate. Both at
 * order 30 and scale 2.
 *
 * <p><b>Which rate.</b> {@code NPV} takes {@link MetricContext#discountRate()} when configured,
 * otherwise the {@code IRR_PERIOD} this run has already solved — §9.5's「缺省用 IRR_PERIOD」. Both carry
 * the same per-period unit, which is what {@link IrrCalculator#npv} expects. {@code XNPV} needs an
 * annual rate for its day-based exponent, so it annualizes the configured periodic rate
 * ({@code (1 + d)^m − 1}, the effective annual equivalent) or falls back to the run's {@code XIRR},
 * which is already an annual rate.
 *
 * <p>Neither rate is ever solved here: a yield-dependent metric that calls {@link
 * IrrCalculator#periodIrr} or {@link IrrCalculator#xirr} itself re-runs a ~100-evaluation root search
 * on a schedule that may have a thousand periods, the wasted work §9.5 rules out. When no rate is
 * configured and the run produced no yield, both metrics return {@code null} — a present value with no
 * discount rate is not a number.
 *
 * <p>At the yield itself {@code NPV} is zero by construction, which is how the metric set checks its
 * own consistency; the residual is the rounding of the published {@code IRR_PERIOD}, not a defect. A
 * rate at or below {@code -1} has no present value at all and no positive base to take a power of:
 * {@code NPV} lets {@link IrrCalculator} reject it, and {@code XNPV} rejects it here before annualizing
 * it. Either way the metric is recorded as not computable rather than failing the run.
 */
public final class NpvMetric implements FinancialMetric {

    private static final int ORDER = 30;

    private final boolean dateBased;
    private final String code;

    private NpvMetric(boolean dateBased) {
        this.dateBased = dateBased;
        this.code = dateBased ? MetricCodes.XNPV : MetricCodes.NPV;
    }

    /**
     * The two present-value metrics: {@code NPV} first, then {@code XNPV}.
     *
     * @return two metrics at order 30; unmodifiable
     */
    public static List<FinancialMetric> instances() {
        return Collections.unmodifiableList(Arrays.asList(
                new NpvMetric(false),
                new NpvMetric(true)));
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
        if (!dateBased) {
            BigDecimal periodRate = MetricSupport.periodRate(context);
            return periodRate == null
                    ? null
                    : MoneyUtils.out(IrrCalculator.npv(context.cashFlows(), periodRate));
        }
        BigDecimal annualRate = annualRate(context);
        return annualRate == null
                ? null
                : MoneyUtils.out(IrrCalculator.xnpv(context.cashFlows(), annualRate));
    }

    /** Effective annual rate of the configured periodic one, else the run's annual yield. */
    private static BigDecimal annualRate(MetricContext context) {
        BigDecimal discountRate = context.discountRate();
        if (discountRate == null) {
            return context.computed(MetricCodes.XIRR);
        }
        if (BigDecimal.ONE.add(discountRate).signum() <= 0) {
            return null;
        }
        return IrrCalculator.annualize(discountRate, context.periodsPerYear());
    }
}
