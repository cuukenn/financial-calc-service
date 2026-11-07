package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.Installment;
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
 * <p>{@code RESIDUAL_VALUE} reports {@link io.github.cuukenn.fincalc.domain.model.LeaseElement#residualValue()},
 * the 残值 / 留购价 the parties agreed to, exactly as §9.5's authority table names it. It is an input
 * of the lease, not a property of the schedule: under
 * {@link io.github.cuukenn.fincalc.domain.model.ResidualHandling#SEPARATE} it is listed as the maturity cash flow
 * of §9.4 and never enters the rents, under
 * {@link io.github.cuukenn.fincalc.domain.model.ResidualHandling#BALLOON_IN_RENT} it is absorbed into them, and in
 * both cases the metric publishes the agreed price. Reading the schedule's outstanding balance there
 * would report {@code 0.00} for a separate residual — the「silently 0」§9.5 forbids.
 * {@link io.github.cuukenn.fincalc.domain.model.LeaseElement#outstandingResidual()} answers a different question
 * (how much principal the schedule still leaves open at maturity) and belongs to the generators, the
 * balance validators and the cash-flow builder.
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
            // §9.5: the option price itself, null-safe (§9.5 forbids a fabricated zero).
            return context.element() == null
                    ? null
                    : MoneyUtils.out(context.element().residualValue());
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
