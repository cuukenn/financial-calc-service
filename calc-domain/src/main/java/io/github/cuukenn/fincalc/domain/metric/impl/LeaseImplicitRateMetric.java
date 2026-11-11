package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.model.CashFlowType;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * CAS 21 / IFRS 16 lessor implicit rate: the rate that discounts the lease receipts (down
 * payment + rents + purchase option) plus the unguaranteed residual to the asset value plus
 * initial direct costs.
 *
 * <p>Under principal = assetValue + IDC - downPayment the answer equals IRR_ANNUAL whenever the
 * unguaranteed residual is zero: it is the pricing rate written from the other end. It separates
 * from IRR only through that non-cash residual, which is added here and nowhere else.
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
        BigDecimal investment = context.element().assetValue()
                .add(zeroIfNull(context.element().initialDirectCosts()));
        BigDecimal down = zeroIfNull(context.element().downPayment());
        BigDecimal unguaranteed = zeroIfNull(context.element().unguaranteedResidual());

        CashFlow first = flows.get(0);
        CashFlow last = flows.get(flows.size() - 1);
        List<CashFlow> priced = new ArrayList<>(flows.size() + 2);
        boolean repriced = false;
        for (CashFlow flow : flows) {
            if (!repriced && flow.direction() == CashFlowDirection.OUTFLOW) {
                priced.add(new CashFlow(flow.sequence(), flow.date(), investment,
                        flow.type(), flow.direction()));
                repriced = true;
            } else {
                priced.add(flow);
            }
        }
        if (!repriced) {
            return null;
        }
        priced.add(new CashFlow(first.sequence(), first.date(), down,
                CashFlowType.PRINCIPAL, CashFlowDirection.INFLOW));
        priced.add(new CashFlow(last.sequence(), last.date(), unguaranteed,
                CashFlowType.RESIDUAL, CashFlowDirection.INFLOW));

        BigDecimal period = IrrCalculator.periodIrr(priced);
        return period == null ? null
                : IrrCalculator.annualize(period, context.periodsPerYear());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
