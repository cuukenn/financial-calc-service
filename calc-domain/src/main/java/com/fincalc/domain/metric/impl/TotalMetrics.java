package com.fincalc.domain.metric.impl;

import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.metric.FinancialMetric;
import com.fincalc.domain.metric.MetricCodes;
import com.fincalc.domain.metric.MetricContext;
import com.fincalc.domain.model.Installment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The six schedule-derived totals of §9.5: {@code TOTAL_PRINCIPAL}, {@code TOTAL_INTEREST},
 * {@code TOTAL_FEE}, {@code TOTAL_TAX}, {@code TOTAL_RENT} and {@code RESIDUAL_VALUE}, all at
 * order 10 and scale 2.
 *
 * <p>One class registers all six because they are the same shape — a column sum over
 * {@code schedule.installments()} — and keeping them together is what stops the column names from
 * drifting apart. {@link #instances()} hands the registry one metric per code.
 *
 * <p>{@code RESIDUAL_VALUE} reports {@link com.fincalc.domain.model.LeaseElement#outstandingResidual()}
 * rather than §9.5's literal {@code residualValue}: what a schedule actually leaves outstanding at
 * maturity. Under {@link com.fincalc.domain.model.ResidualHandling#BALLOON_IN_RENT} the two are the
 * same, and under {@link com.fincalc.domain.model.ResidualHandling#SEPARATE} the option price is
 * already listed as the maturity cash flow of §9.4, so reporting the raw option price here would
 * contradict a schedule that ends at zero.
 */
public final class TotalMetrics implements FinancialMetric {

    private static final int ORDER = 10;

    /** Which column of the schedule this instance sums. */
    private enum Column {
        PRINCIPAL, INTEREST, FEE, TAX, RENT, RESIDUAL
    }

    private final String code;
    private final Column column;

    private TotalMetrics(String code, Column column) {
        this.code = code;
        this.column = column;
    }

    /**
     * One metric per total code, in §9.5 table order.
     *
     * @return six metrics, all at order 10; unmodifiable
     */
    public static List<FinancialMetric> instances() {
        return Collections.unmodifiableList(Arrays.asList(
                new TotalMetrics(MetricCodes.TOTAL_PRINCIPAL, Column.PRINCIPAL),
                new TotalMetrics(MetricCodes.TOTAL_INTEREST, Column.INTEREST),
                new TotalMetrics(MetricCodes.TOTAL_FEE, Column.FEE),
                new TotalMetrics(MetricCodes.TOTAL_TAX, Column.TAX),
                new TotalMetrics(MetricCodes.TOTAL_RENT, Column.RENT),
                new TotalMetrics(MetricCodes.RESIDUAL_VALUE, Column.RESIDUAL)));
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
        if (column == Column.RESIDUAL) {
            return context.element() == null
                    ? null
                    : MoneyUtils.out(context.element().outstandingResidual());
        }
        if (context.schedule() == null) {
            return null;
        }
        List<BigDecimal> amounts = new ArrayList<>();
        for (Installment installment : MetricSupport.rows(context)) {
            amounts.add(cellOf(installment));
        }
        return MoneyUtils.out(MoneyUtils.sum(amounts));
    }

    /** The summed column of one row; a {@code null} cell contributes zero. */
    private BigDecimal cellOf(Installment installment) {
        switch (column) {
            case PRINCIPAL:
                return installment.principalDue();
            case INTEREST:
                return installment.interestPaid();
            case FEE:
                return installment.fee();
            case TAX:
                return installment.tax();
            case RENT:
                return installment.rentDue();
            default:
                throw new IllegalStateException("no schedule column for " + code);
        }
    }
}
