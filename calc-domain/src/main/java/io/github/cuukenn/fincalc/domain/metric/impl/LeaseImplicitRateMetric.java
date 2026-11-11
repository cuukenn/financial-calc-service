package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code LEASE_IMPLICIT_RATE} — the lease implicit rate of CAS 21 (AGENTS.md §2.1): the rate that
 * discounts the lease receipts to the <b>asset value</b> plus initial direct costs.
 *
 * <p><b>Why this metric exists.</b> Without it, {@code LeaseElement.assetValue} would change no
 * number, and AGENTS.md §5 M-01 forbids adding an input that does not move the calculation. The
 * implicit rate is the only quantity that is defined against the asset value rather than the
 * financed principal — {@code IRR_PERIOD} still measures the yield on the principal, which is a
 * different question.
 *
 * <p><b>How it is solved.</b> The run's lessor cash flows are re-priced: the opening outflow
 * (the principal advanced) is replaced by the asset value, every receipt is unchanged, and the
 * same {@link IrrCalculator} solves the root. The answer is annualised with the run's
 * {@code periodsPerYear} so it is comparable with {@code IRR_ANNUAL} and {@code XIRR}.
 *
 * <p><b>Known limitations, stated rather than hidden.</b> v1 does not model initial direct costs
 * (they are absent from the element), so the denominator is the asset value alone; and the
 * unguaranteed residual value is not an input yet, so the receipts carry only the purchase option
 * price. Both omissions are recorded in the design document's errata table.
 */
public final class LeaseImplicitRateMetric implements FinancialMetric {

    @Override
    public String code() {
        return MetricCodes.LEASE_IMPLICIT_RATE;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        if (context.element() == null || context.element().assetValue() == null) {
            return null;
        }
        List<CashFlow> flows = MetricSupport.flowsFor(context);
        if (flows.isEmpty()) {
            return null;
        }
        List<CashFlow> priced = new ArrayList<>(flows.size());
        boolean repriceadOpening = false;
        for (CashFlow flow : flows) {
            if (!repriceadOpening && flow.direction() == CashFlowDirection.OUTFLOW) {
                priced.add(new CashFlow(flow.sequence(), flow.date(), context.element().assetValue(),
                        flow.type(), flow.direction()));
                repriceadOpening = true;
            } else {
                priced.add(flow);
            }
        }
        if (!repriceadOpening) {
            return null;
        }
        BigDecimal periodRate = IrrCalculator.periodIrr(priced);
        return periodRate == null ? null
                : IrrCalculator.annualize(periodRate, context.periodsPerYear());
    }
}
