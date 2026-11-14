package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The three periodic / annualized IRR codes of §9.5: {@code IRR_PERIOD} solves the flow set,
 * {@code IRR_ANNUAL} compounds it and {@code IRR_ANNUAL_SIMPLE} multiplies it. All three are order
 * 20, and {@link #instances()} must be registered in that same order because a tie in
 * {@link io.github.cuukenn.fincalc.common.spi.OrderedExtension#order()} keeps registration order — the two annual
 * variants read the periodic root out of the run and must see it after it has been solved.
 *
 * <p>The annual variants deliberately do not call {@link IrrCalculator#periodIrr} themselves. One
 * root solve is about a hundred NPV evaluations; on a 1000-period schedule the yield metrics would
 * become the cost of the whole calculation (§9.5 forbids that wasted work). They consume
 * {@code ctx.computed(IRR_PERIOD)} instead, which is the value {@code IRR_PERIOD} published at its
 * §9.5 scale of 10 — the same number the output carries, so no metric can disagree with it.
 *
 * <p><b>Perspective (R103).</b> The flows come from {@link MetricSupport#flowsFor(MetricContext)},
 * so a {@code LESSEE} run solves the same root over the mirrored flow set and publishes it under the
 * same {@code IRR_*} codes — the perspective flips the direction of the money, and a global sign flip
 * leaves the zeros of NPV where they were. The borrower family keeps its own record
 * ({@code BORROWER_IRR_PERIOD}) so a caller can tell which view asked.
 *
 * <p>Scale: {@code IRR_PERIOD} is rounded to {@link io.github.cuukenn.fincalc.common.money.MoneyScale#INTERMEDIATE};
 * the annual pair arrives from {@link IrrCalculator} already at {@link
 * io.github.cuukenn.fincalc.common.money.MoneyScale#CALC} and is passed through unrounded, because rounding an
 * annual rate twice is how a reconciliation discrepancy gets invented.
 *
 * <p><b>Which root.</b> A flow set with several sign changes has several rates that zero its NPV, and
 * §9.5's tie-break「取最接近名义利率的最小正根」needs the element's nominal rate next to the solver
 * result. That pairing is the metric-assembly task's job; {@code IRR_PERIOD} publishes whichever root
 * {@link IrrCalculator} landed on, exactly as {@link IrrCalculator#hasSingleSignChange} warns.
 */
public final class IrrMetrics implements FinancialMetric {

    private static final int ORDER = 20;

    /** Which of the three yield codes this instance publishes. */
    private enum Variant {
        PERIOD, ANNUAL_COMPOUND, ANNUAL_SIMPLE
    }

    private final String code;
    private final Variant variant;

    private IrrMetrics(String code, Variant variant) {
        this.code = code;
        this.variant = variant;
    }

    /**
     * The three yield metrics, in dependency order: periodic root first, then its two
     * annualizations.
     *
     * @return three metrics at order 20; unmodifiable
     */
    public static List<FinancialMetric> instances() {
        return Collections.unmodifiableList(Arrays.asList(
                new IrrMetrics(MetricCodes.IRR_PERIOD, Variant.PERIOD),
                new IrrMetrics(MetricCodes.IRR_ANNUAL, Variant.ANNUAL_COMPOUND),
                new IrrMetrics(MetricCodes.IRR_ANNUAL_SIMPLE, Variant.ANNUAL_SIMPLE)));
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
        List<CashFlow> flows = MetricSupport.flowsFor(context);
        if (flows.isEmpty()) {
            return null;
        }
        if (variant == Variant.PERIOD) {
            BigDecimal raw = IrrCalculator.periodIrr(flows);
            // Hand the unrounded root — and the flow set it is a root of — to the run before this
            // metric publishes its §9.5 rounding. The engine's §9.7 chain prices a rent-caliber
            // stream; reading this root is what keeps the run to one solve of that stream instead of
            // two identical ones, and reading it rather than the published
            // IRR_PERIOD is what keeps every §9.7 row cent-for-cent.
            context.rootHandoff().record(raw, flows);
            return MoneyUtils.mid(raw);
        }
        if (!MetricSupport.annualizable(context)) {
            // IRREGULAR: no annual compounding convention exists, so no annual view either.
            return null;
        }
        BigDecimal periodIrr = context.computed(MetricCodes.IRR_PERIOD);
        if (periodIrr == null) {
            // The periodic root is absent (not solved yet, or not computable for this flow set).
            // Solving it here would duplicate the run's one solve, so the annual view is not
            // computable either.
            return null;
        }
        return variant == Variant.ANNUAL_COMPOUND
                ? IrrCalculator.annualize(periodIrr, context.periodsPerYear())
                : IrrCalculator.simpleAnnualize(periodIrr, context.periodsPerYear());
    }
}
