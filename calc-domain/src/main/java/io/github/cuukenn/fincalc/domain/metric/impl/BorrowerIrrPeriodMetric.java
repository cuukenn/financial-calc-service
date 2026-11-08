package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code BORROWER_IRR_PERIOD} at order 20 (R103): the borrower-view root — the one root solve the
 * {@code BORROWER_*} family pays for, exactly as {@code IRR_PERIOD} is the one solve of the §9.5
 * yield family.
 *
 * <p>The solve itself is the §9.5 one: {@link MetricSupport#flowsFor(MetricContext)} hands the
 * flipped flow set to {@link IrrCalculator#periodIrr}, the same calculator {@code IRR_PERIOD} uses.
 * The flip keeps every amount positive and moves the sign into the {@code direction} field, so the
 * lessee's funded principal is an inflow and the rents are outflows without a negative amount
 * anywhere (AGENTS.md 补充口径规则 3).
 *
 * <p><b>It is the same root, republished.</b> A global sign flip negates the NPV function and
 * leaves its zeros unchanged, so this root always equals {@code IRR_PERIOD} to the last digit. It is
 * registered as its own code only so the two {@code BORROWER_IRR_ANNUAL*} metrics can read a
 * <em>recorded</em> root through {@link MetricContext#computed(String)} instead of paying for a
 * second solve; it labels the borrower view, it does not reprice the deal (R121).
 *
 * <p>Scale: {@link io.github.cuukenn.fincalc.common.money.MoneyScale#INTERMEDIATE} (10), the §9.5
 * scale for a solved periodic root — a rate finer than the solver's {@code 1e-10} tolerance would
 * be false precision. Present-but-null on a {@link CashFlowPerspective#LESSOR} run and when the flow
 * set has no root.
 */
public final class BorrowerIrrPeriodMetric implements FinancialMetric {

    /**
     * The code this solve publishes, and the key {@link BorrowerIrrMetrics} reads it back from —
     * one name for the record, so the writer and the readers cannot drift apart.
     */
    public static final String BORROWER_IRR_PERIOD = MetricCodes.BORROWER_IRR_PERIOD;

    private static final int ORDER = 20;

    @Override
    public String code() {
        return BORROWER_IRR_PERIOD;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        if (context.perspective() != CashFlowPerspective.LESSEE) {
            return null;
        }
        List<CashFlow> flows = MetricSupport.flowsFor(context);
        if (flows.isEmpty()) {
            return null;
        }
        return MoneyUtils.mid(IrrCalculator.periodIrr(flows));
    }
}
