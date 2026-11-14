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
 *
 * <p><b>One solve per root problem.</b> The priced stream is matched against the
 * run's {@link io.github.cuukenn.fincalc.domain.metric.PeriodicRootHandoff} ledger before solving:
 * with no unguaranteed residual it <em>is</em> the event table and {@code IRR_PERIOD}'s unrounded
 * root is reused; with one it is solved here and recorded, so the engine's §9.7 chain — which prices
 * the same net investment including that residual (CAS 21 第三十八条) — reads this root rather than
 * solving the identical stream a second time. In both directions the reuse is content-decided: the
 * ledger matches only a flow set that is the same root problem (same indices, same signed amounts,
 * or the whole-view mirror R103 defines), never by assumption.
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
        CashFlow last = flows.get(flows.size() - 1);
        BigDecimal unguaranteed = context.element().unguaranteedResidual() == null
                ? BigDecimal.ZERO : context.element().unguaranteedResidual();
        List<CashFlow> priced = new ArrayList<>(flows);
        if (unguaranteed.signum() > 0) {
            priced.add(new CashFlow(last.sequence(), last.date(), unguaranteed,
                    CashFlowType.RESIDUAL, CashFlowDirection.INFLOW));
        }

        // One solve per root problem. When the unguaranteed residual is zero and
        // principal = asset value + IDC, this stream IS the event table IRR_PERIOD solved over —
        // the javadoc above states that equality — so the ledger hands back its unrounded root and
        // this code costs no solve at all. When the residual is priced in, the stream genuinely
        // differs and earns its own solve; recording it lets the engine's §9.7 chain reuse it
        // instead of solving the identical net-investment stream a second time.
        BigDecimal period = context.rootHandoff().rootFor(priced);
        if (period == null) {
            period = IrrCalculator.periodIrr(priced);
            context.rootHandoff().record(period, priced);
        }
        if (period == null || !MetricSupport.annualizable(context)) {
            // IRREGULAR: the CAS 21 measure chain solves the period rate, but the annual view has
            // no compounding convention.
            return null;
        }
        return IrrCalculator.annualize(period, context.periodsPerYear());
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
