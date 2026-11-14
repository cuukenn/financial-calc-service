package io.github.cuukenn.fincalc.domain.metric.impl;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetric;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code BORROWER_TOTAL_REPAYMENT} at order 10 (R103/R120): what the lessee repays over the whole
 * term — Σ {@code rentDue}, plus the residual it settles separately, nothing else.
 *
 * <p><b>Only {@code rentDue}, never its components.</b> A row's rent already <em>is</em>
 * {@code principalDue + interestPaid + fee + tax} ({@code AbstractAmortizationGenerator}, and
 * AGENTS.md check 4 asserts Σ租金 = Σ本金 + Σ利息 + Σ费用 + Σ税), so a total that added
 * Σ {@code fee} or Σ {@code tax} on top of Σ {@code rentDue} would count that money twice. The
 * column sum here is the rent column and nothing else.
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
 * <p><b>Why the deposit is excluded (R120/R123).</b> A v1 deposit is a refundable §9.4 leg: the
 * lessor takes it at t0 and returns it at maturity ({@code StandardCashFlowBuilder} emits the pair
 * under the {@code INCLUDE_DEPOSIT} basis), so it is not a repayment and counting it would inflate
 * the borrower's cost by money it gets back. This build has exactly one deposit posture —
 * separately collected and refundable — and no basis that nets it off the funded principal, so
 * there is no second branch here and no test for one: the {@code DepositBasis} type the R103 brief
 * named does not exist in this codebase.
 *
 * <p><b>P-08.</b> The cells are summed first and the total is rounded once at the output boundary;
 * rounding every row first is how a cent of reconciliation difference gets invented.
 *
 * <p>Present-but-null on a {@link CashFlowPerspective#LESSOR} run and when the context carries no
 * schedule — mirroring {@code TotalMetrics}' null-safety instead of fabricating a 0.00. This is a
 * 借款方视角 presentation aid, not a regulatory metric.
 *
 * <p><b>Guaranteed residual 的口径（ADR-0001）.</b> The lessee's side is added at full value,
 * which is the CAS 21 第三十八条（五）/ IFRS 16 Appendix A <em>lessor</em> rule. CAS 21
 * 第十八条（五） counts only the amounts <em>expected to be payable</em> under a residual value
 * guarantee on the lessee's side, so this figure is a deliberately conservative lessee reading and
 * must be labelled as such (AGENTS.md M-04). Closing that gap needs an expected-payable input,
 * which would change a cash flow and is therefore an A-class field (M-01) - not added.
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
        if (context.perspective() != CashFlowPerspective.LESSEE || context.schedule() == null) {
            return null;
        }
        List<BigDecimal> amounts = new ArrayList<>();
        for (Installment installment : MetricSupport.rows(context)) {
            amounts.add(installment.rentDue());
        }
        amounts.add(terminalReceipts(context.element()));
        return MoneyUtils.out(MoneyUtils.sum(amounts));
    }

    /**
     * Money the lessee pays at maturity on top of the rents (CAS 21 第三十七条 租赁收款额): the
     * purchase option price when it is settled separately (under
     * {@link ResidualHandling#BALLOON_IN_RENT} it is already inside the rents) plus the guaranteed
     * residual, which is always a distinct receipt.
     */
    private static BigDecimal terminalReceipts(LeaseElement element) {
        if (element == null) {
            return MoneyUtils.ZERO;
        }
        BigDecimal option = element.residualHandling() == ResidualHandling.SEPARATE
                ? MoneyUtils.zeroIfNull(element.residualValue())
                : MoneyUtils.ZERO;
        return option.add(MoneyUtils.zeroIfNull(element.guaranteedResidual()));
    }
}
