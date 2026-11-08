package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.metric.MetricPerspective;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code BORROWER_TOTAL_REPAYMENT} at order 10 (R103): what the lessee hands over across the whole
 * term — Σ {@code rentDue} + Σ {@code fee} + Σ {@code tax}, plus the residual it settles separately
 * and the deposit it pays separately.
 *
 * <p><b>Why the residual is read from the element, not the schedule.</b> Under
 * {@link ResidualHandling#SEPARATE} the 留购价 never enters the rents (§9.4 lists it as the maturity
 * event) and the schedule amortizes the whole principal, so a schedule-only sum would miss it —
 * the「silently 0」§9.5 forbids. {@link TotalMetrics} reports the same price as
 * {@code RESIDUAL_VALUE}; here it is the lessee's extra outlay. Under
 * {@link ResidualHandling#BALLOON_IN_RENT} it is already inside the rents (the schedule is required
 * to end with it as the closing balance, §9.6 {@code TAIL_BALANCE}), so it contributes 0 and is not
 * counted twice.
 *
 * <p><b>Why the deposit is always added.</b> v1 has one deposit basis only: a refundable deposit is
 * handed over at t0 and returned at maturity, so it is always collected separately from the funded
 * principal and never nets against it ({@code StandardCashFlowBuilder}: 「v1 deposits are refundable
 * only, so they never net against the t0 principal」). There is no netted-off-principal basis in
 * this build to switch to; when one arrives, this is the one place that has to learn about it.
 *
 * <p><b>The fee / tax terms, as specified.</b> R103 defines the total as Σ {@code rentDue} +
 * Σ {@code fee} + Σ {@code tax} + residual + deposit, and this class implements exactly that.
 * {@link Installment#rentDue()} is documented as {@code principal + interest + fee + tax}, so on a
 * schedule whose fee or VAT is folded into the rent the two extra sums would count that money twice;
 * no v1 generator produces such a row (fees are UPFRONT-only and the tax column is part of the rent
 * it belongs to), and the golden fixtures carry both at zero. When Plan 2's {@code FeeAllocator} or
 * a VAT split starts filling those columns, this sum is the line that has to decide which of the two
 * conventions the borrower total follows.
 *
 * <p><b>P-08.</b> The column cells are summed first and the total is rounded once at the output
 * boundary; rounding every row first is how a cent of reconciliation difference gets invented.
 *
 * <p>Present-but-null on a {@link MetricPerspective#LESSOR} run and when the context carries no
 * schedule — mirroring {@code TotalMetrics}' null-safety instead of fabricating a 0.00. This is a
 * 借款方视角 presentation aid, not a regulatory metric.
 */
public final class BorrowerTotalRepaymentMetric implements FinancialMetric {

    private static final int ORDER = 10;

    @Override
    public String code() {
        return MetricCodes.BORROWER_TOTAL_REPAYMENT;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public BigDecimal compute(MetricContext context) {
        if (context.perspective() != MetricPerspective.LESSEE || context.schedule() == null) {
            return null;
        }
        List<BigDecimal> amounts = new ArrayList<>();
        for (Installment installment : MetricSupport.rows(context)) {
            amounts.add(installment.rentDue());
            amounts.add(installment.fee());
            amounts.add(installment.tax());
        }
        LeaseElement element = context.element();
        amounts.add(separateResidual(element));
        amounts.add(separateDeposit(element));
        return MoneyUtils.out(MoneyUtils.sum(amounts));
    }

    /** The option price the lessee pays at maturity on top of the rents; 0 when it is inside them. */
    private static BigDecimal separateResidual(LeaseElement element) {
        if (element == null || element.residualHandling() != ResidualHandling.SEPARATE) {
            return MoneyUtils.ZERO;
        }
        return MoneyUtils.zeroIfNull(element.residualValue());
    }

    /** The deposit handed over at t0 — v1 collects every deposit separately (see the class javadoc). */
    private static BigDecimal separateDeposit(LeaseElement element) {
        return element == null ? MoneyUtils.ZERO : MoneyUtils.zeroIfNull(element.deposit());
    }
}
