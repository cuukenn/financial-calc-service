package com.fincalc.domain.metric.impl;

import com.fincalc.common.money.MoneyScale;
import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.metric.FinancialMetric;
import com.fincalc.domain.metric.MetricCodes;
import com.fincalc.domain.metric.MetricContext;
import com.fincalc.domain.model.Installment;
import java.math.BigDecimal;

/**
 * {@code AVG_OUTSTANDING_BALANCE = Σ(opening_k × days_k) / Σ days_k} at order 10 and scale 2: the
 * principal the lessor actually had at risk, weighted by how long each period held it (§9.5).
 *
 * <p>Day weighting matters: under ACT conventions the periods have different lengths, and a plain
 * average of the opening balances would price a long first period like a short one.
 *
 * <p>A schedule with no rows, or rows that carry no accrual days, has no weighting to divide by and
 * answers {@code null} rather than 0.
 */
public final class AverageBalanceMetric implements FinancialMetric {

    @Override
    public String code() {
        return MetricCodes.AVG_OUTSTANDING_BALANCE;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        if (context.schedule() == null) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal days = BigDecimal.ZERO;
        for (Installment installment : MetricSupport.rows(context)) {
            BigDecimal periodDays = BigDecimal.valueOf(installment.days());
            days = days.add(periodDays);
            weighted = weighted.add(MoneyUtils.zeroIfNull(installment.openingBalance())
                    .multiply(periodDays));
        }
        if (days.signum() == 0) {
            return null;
        }
        return weighted.divide(days, MoneyScale.OUTPUT, MoneyScale.ROUNDING);
    }
}
