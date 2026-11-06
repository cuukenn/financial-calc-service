package com.fincalc.domain.metric.impl;

import com.fincalc.common.money.MoneyScale;
import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.metric.FinancialMetric;
import com.fincalc.domain.metric.MetricCodes;
import com.fincalc.domain.metric.MetricContext;
import com.fincalc.domain.model.Installment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code RENTAL_RATE = TOTAL_RENT / principal} at order 10 and scale 6 (§9.5).
 *
 * <p>The rent sum is recomputed here instead of reading {@link MetricCodes#TOTAL_RENT} through
 * {@link MetricContext#computed(String)}: both metrics sit in the same order tier, and a
 * dependency on the registry's tie-breaking order would be an invisible coupling for a sum that
 * costs one pass over the rows.
 *
 * <p>A zero principal would divide by nothing, so the metric answers {@code null};
 * {@link com.fincalc.domain.model.LeaseElement} already rejects one, which makes that branch a
 * guard for hand-built contexts rather than a business case.
 */
public final class RentalRateMetric implements FinancialMetric {

    /** §9.5 pins the rental rate at six scales. */
    private static final int RATE_SCALE = 6;

    @Override
    public String code() {
        return MetricCodes.RENTAL_RATE;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        if (context.schedule() == null || context.element() == null) {
            return null;
        }
        BigDecimal principal = context.element().principal();
        if (principal == null || principal.signum() == 0) {
            return null;
        }
        List<BigDecimal> rents = new ArrayList<>();
        for (Installment installment : MetricSupport.rows(context)) {
            rents.add(installment.rentDue());
        }
        BigDecimal totalRent = MoneyUtils.sum(rents);
        // An explicit scale on the divide: the quotient is not terminating in general.
        return totalRent.divide(principal, RATE_SCALE, MoneyScale.ROUNDING);
    }
}
