package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code MIRR} at order 20 (§9.5): positive flows compound forward at the reinvestment rate,
 * negative flows discount back at the finance rate, and the bridge between the two is annualized.
 *
 * <p>The two rates are <b>annual</b> in {@link MetricContext} — {@link IrrCalculator#mirr} divides
 * them by {@code periodsPerYear} itself. When the run configures neither, this metric defaults both
 * to the element's {@link io.github.cuukenn.fincalc.domain.model.LeaseElement#nominalRate()}: that is what a
 * lease is being priced against, and it is the only rate a context is guaranteed to have. A context
 * with no element has no default to fall back on, so the metric answers {@code null} rather than
 * inventing a rate.
 *
 * <p>The value {@link IrrCalculator#mirr} returns is <b>already an annual rate</b> — its exponent is
 * {@code periodsPerYear / horizon}. Annualizing it a second time would report the yield of a
 * compounding cycle rather than of a year. It arrives at {@link
 * io.github.cuukenn.fincalc.common.money.MoneyScale#CALC} and is published unrounded.
 */
public final class MirrMetric implements FinancialMetric {

    @Override
    public String code() {
        return MetricCodes.MIRR;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        List<CashFlow> flows = MetricSupport.flowsFor(context);
        if (flows.isEmpty()) {
            return null;
        }
        BigDecimal nominal = context.element() == null ? null : context.element().nominalRate();
        BigDecimal reinvestRate = context.reinvestRate() != null ? context.reinvestRate() : nominal;
        BigDecimal financeRate = context.financeRate() != null ? context.financeRate() : nominal;
        if (reinvestRate == null || financeRate == null) {
            return null;
        }
        return IrrCalculator.mirr(flows, reinvestRate, financeRate, context.periodsPerYear());
    }
}
