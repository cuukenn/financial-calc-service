package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.metric.MetricPerspective;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The two annual views of the borrower yield (R103): {@code BORROWER_IRR_ANNUAL} compounds the
 * periodic root and {@code BORROWER_IRR_ANNUAL_SIMPLE} multiplies it, both at order 30 and both
 * reading {@link BorrowerIrrPeriodMetric#BORROWER_IRR_PERIOD} out of the run.
 *
 * <p>Shaped exactly like {@link IrrMetrics}, and for the same reason: the annualizations must
 * <b>never solve</b>. One root solve is about a hundred NPV evaluations, and on a 1000-period
 * schedule a second solve per annualization is the wasted work §9.5 forbids. They consume the root
 * the order-20 borrower metric published, at its §9.5 scale of 10, so no two borrower codes can
 * disagree about the yield.
 *
 * <p>Registration order inside the tier is the dependency contract: {@link #instances()} must be
 * registered after {@link BorrowerIrrPeriodMetric} (see {@code MetricRegistry.defaults()}).
 *
 * <p>Both are present-but-null on a {@link MetricPerspective#LESSOR} run and when the borrower root
 * is not computable — a rate that does not exist is not zero. The annualizations themselves are
 * exact transformations of the published root, so they arrive at
 * {@link io.github.cuukenn.fincalc.common.money.MoneyScale#CALC} from {@link IrrCalculator} and are
 * passed through unrounded; rounding an annual rate twice is how a reconciliation difference gets
 * invented.
 */
public final class BorrowerIrrMetrics implements FinancialMetric {

    private static final int ORDER = 30;

    /** Which of the two annual codes this instance publishes. */
    private enum Variant {
        ANNUAL_COMPOUND, ANNUAL_SIMPLE
    }

    private final String code;
    private final Variant variant;

    private BorrowerIrrMetrics(String code, Variant variant) {
        this.code = code;
        this.variant = variant;
    }

    /**
     * The two annualizations, in dependency order (both read the same recorded root).
     *
     * @return two metrics at order 30; unmodifiable
     */
    public static List<FinancialMetric> instances() {
        return Collections.unmodifiableList(Arrays.asList(
                new BorrowerIrrMetrics(MetricCodes.BORROWER_IRR_ANNUAL, Variant.ANNUAL_COMPOUND),
                new BorrowerIrrMetrics(MetricCodes.BORROWER_IRR_ANNUAL_SIMPLE,
                        Variant.ANNUAL_SIMPLE)));
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
        if (context.perspective() != MetricPerspective.LESSEE) {
            return null;
        }
        BigDecimal periodIrr = context.computed(BorrowerIrrPeriodMetric.BORROWER_IRR_PERIOD);
        if (periodIrr == null) {
            // No borrower root in the run (never solved, or not computable for this flow set):
            // solving it here would duplicate the run's one solve, so the annual view is not
            // computable either.
            return null;
        }
        return variant == Variant.ANNUAL_COMPOUND
                ? IrrCalculator.annualize(periodIrr, context.periodsPerYear())
                : IrrCalculator.simpleAnnualize(periodIrr, context.periodsPerYear());
    }
}
