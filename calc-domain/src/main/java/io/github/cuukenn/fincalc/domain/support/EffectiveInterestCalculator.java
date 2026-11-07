package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.common.money.MoneyScale;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.model.EffectiveInterestRow;
import io.github.cuukenn.fincalc.domain.model.EffectiveInterestTable;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The §9.7 effective-interest amortization table (实际利率法摊销表), built alongside — never
 * inside — the nominal-interest schedule the generators produce.
 *
 * <p><b>口径</b> (one row per {@link Installment}, in schedule order):
 * <ol>
 *   <li>{@code EIR_period = periodIrr}, the caller's periodic yield — §9.7's 「EIR_period =
 *       IRR_PERIOD（与现金流同口径）」. The rate itself is produced by
 *       {@link IrrCalculator#periodIrr} over the §9.4 event table; this class prices with it,
 *       it does not solve for it.</li>
 *   <li>{@code interestIncome = openingNetInvestment × EIR_period} at {@link MoneyScale#OUTPUT};
 *       no division happens anywhere in this class.</li>
 *   <li>{@code cashReceived = installment.rentDue()}.</li>
 *   <li>{@code principalRecovery = cashReceived − interestIncome};
 *       {@code closingNetInvestment = openingNetInvestment − principalRecovery}.</li>
 * </ol>
 *
 * <p><b>期初净投资 (row 1).</b> §9.7 defines it as 「期初余额 − 未摊销费用」; v1 has no prepaid /
 * unamortized-fee state in the domain — fees arrive per period on {@link Installment#fee()} —
 * so the term reduces to the schedule's own opening balance, which the generators seed with
 * {@code element.principal()} (design ledger ruling R46). When 初始直接费用 enters the model,
 * the subtraction plugs in at {@code rowOneOpening} below.
 *
 * <p><b>Terminal balance.</b> §9.7's 「末期 closingNetInvestment 必须归零」 is applied against the
 * schedule's own last {@link Installment#closingBalance()} rather than literal zero (design
 * ledger ruling R47): under {@code ResidualHandling#BALLOON_IN_RENT} the schedule legitimately
 * stops at the residual while {@code cashReceived} carries {@code rentDue} only, and forcing 0
 * there would swallow the whole residual into 末期利息. For fully-amortizing schedules the target
 * is 0.00 and the spec sentence holds verbatim.
 *
 * <p><b>Residuals and pricing consistency.</b> The flip side of ruling R47: a residual that
 * the rate prices but {@code cashReceived} never receives is a structural inconsistency, not
 * cent drift. The §9.4 event table appends the residual as a maturity inflow, and
 * {@code IrrCalculator} discounts each flow by its list {@code sequence}, so that inflow sits
 * one discount step past the last rent; a table fed that root while charging {@code rentDue}
 * only lands one residual-step short of where the schedule stops — hundreds on a 100 000
 * balloon, far outside the closing tolerance — and this class raises {@code FCS-CALC-3006}
 * rather than fabricate the difference as 末期利息. Feeding a rate that prices the same cash
 * stream {@code cashReceived} sees, or deciding a residual-bearing contract gets no
 * effective-interest table, is the engine task's call; both behaviours are pinned by tests.
 *
 * <p><b>Tail absorption.</b> The final row's {@code interestIncome} absorbs the difference
 * between the computed final closing and the target — but only within
 * {@code 0.01 × periods} (see {@link #CLOSING_TOLERANCE_PER_PERIOD}); anything wider is a
 * structural inconsistency and raises {@code FCS-CALC-3006} per §9.7.
 *
 * <p><b>Rate legality.</b> A negative rate raises {@code FCS-CALC-3007} and a missing one
 * (no root found) raises {@code FCS-CALC-3003} as a defensive backstop — the engine task that
 * calls this guards against both. A rate of exactly 0 is accepted: a 0 % lease is legitimate
 * and its table (interest income 0.00, every rent is principal recovery) is well-defined; an
 * inconsistent zero-rate stream is caught by the closing check instead (design ledger ruling
 * R49).
 *
 * <p><b>逐期利息差额.</b> §9.7 asks for the per-period interest difference against the nominal
 * method; the row shape stays exactly as briefed (design ledger ruling R50), so the difference
 * is delivered by the join: {@code row.interestIncome() − installment.interestPaid()} matched
 * on {@code row.periodNo() == installment.periodNo()}. {@link
 * EffectiveInterestTable#totalInterestDifference()} is that join summed.
 *
 * <p><b>Invariant worth naming.</b> Because the terminal row is forced to the target,
 * {@code Σ interestIncome = Σ rentDue − rowOneOpening + target} exactly; for a schedule the
 * generators produced with zero fees and tax this makes the total difference
 * Σ {@code fee + tax} — 0.00 for a plain loan — which is what the reconciliation test pins.
 */
public final class EffectiveInterestCalculator {

    /**
     * Tail bound per period: each period's scale-2 interest rounding moves the chain by at most
     * half a cent, and a rate published at scale 10 ({@code IRR_PERIOD}) contributes at most
     * 5e-11 × principal per period — the 12-period reference schedule lands near a cent, while
     * a fee/deposit-vs-rent structural mismatch shows up as thousands, not cents. A computed
     * final closing further than {@code 0.01 × periods} from the target is therefore not
     * rounding noise but a broken input pair (design ledger ruling R51).
     */
    private static final BigDecimal CLOSING_TOLERANCE_PER_PERIOD = new BigDecimal("0.01");

    private EffectiveInterestCalculator() {
    }

    /**
     * Builds the effective-interest table for {@code schedule} at the periodic yield
     * {@code periodIrr}.
     *
     * @param schedule  the nominal-method schedule to price; must have at least one installment
     * @param periodIrr the periodic effective interest rate from {@link IrrCalculator#periodIrr}
     * @return an immutable table whose last closing net investment equals the schedule's own
     *         last closing balance
     * @throws BusinessException FCS-CALC-3003 when {@code periodIrr} is {@code null} (no root
     *         found); FCS-CALC-3007 when it is negative; FCS-CALC-3006 when the table cannot
     *         close to the schedule's terminal balance within {@code 0.01 × periods}
     */
    public static EffectiveInterestTable calculate(Schedule schedule, BigDecimal periodIrr) {
        Objects.requireNonNull(schedule, "schedule");
        if (periodIrr == null) {
            throw new BusinessException(CalcErrorCode.IRR_NOT_CONVERGED);
        }
        if (periodIrr.signum() < 0) {
            throw new BusinessException(CalcErrorCode.EFFECTIVE_INTEREST_RATE_INVALID,
                    periodIrr.toPlainString());
        }
        List<Installment> installments = schedule.installments();
        if (installments.isEmpty()) {
            throw new BusinessException(CalcErrorCode.EFFECTIVE_INTEREST_TABLE_NOT_CLOSING,
                    "schedule 没有期次，摊销表无末期可收敛");
        }

        List<EffectiveInterestRow> rows = new ArrayList<>(installments.size());
        BigDecimal opening = rowOneOpening(installments.get(0));
        BigDecimal totalInterestIncome = MoneyUtils.ZERO;
        BigDecimal totalInterestPaid = MoneyUtils.ZERO;
        for (Installment installment : installments) {
            BigDecimal cashReceived = MoneyUtils.scale(installment.rentDue(),
                    MoneyScale.OUTPUT);
            BigDecimal interestIncome = MoneyUtils.scale(
                    opening.multiply(periodIrr), MoneyScale.OUTPUT);
            BigDecimal principalRecovery = cashReceived.subtract(interestIncome);
            BigDecimal closingNetInvestment = opening.subtract(principalRecovery);

            rows.add(new EffectiveInterestRow(installment.periodNo(), opening, interestIncome,
                    cashReceived, principalRecovery, closingNetInvestment));
            totalInterestIncome = totalInterestIncome.add(interestIncome);
            totalInterestPaid = totalInterestPaid.add(MoneyUtils.zeroIfNull(
                    installment.interestPaid()));
            opening = closingNetInvestment;
        }

        EffectiveInterestRow lastRow = rows.get(rows.size() - 1);
        BigDecimal target = MoneyUtils.scale(
                installments.get(installments.size() - 1).closingBalance(), MoneyScale.OUTPUT);
        BigDecimal adjustment = target.subtract(lastRow.closingNetInvestment());
        BigDecimal tolerance = CLOSING_TOLERANCE_PER_PERIOD.multiply(
                BigDecimal.valueOf(installments.size()));
        if (adjustment.abs().compareTo(tolerance) > 0) {
            throw new BusinessException(CalcErrorCode.EFFECTIVE_INTEREST_TABLE_NOT_CLOSING,
                    "末期净投资 " + lastRow.closingNetInvestment().toPlainString()
                            + " 距目标 " + target.toPlainString() + " 差额 "
                            + adjustment.abs().toPlainString() + " 超出容差 "
                            + tolerance.toPlainString());
        }
        if (adjustment.signum() != 0) {
            BigDecimal adjustedInterest = MoneyUtils.scale(
                    lastRow.interestIncome().add(adjustment), MoneyScale.OUTPUT);
            BigDecimal adjustedRecovery = lastRow.cashReceived().subtract(adjustedInterest);
            BigDecimal adjustedClosing = lastRow.openingNetInvestment().subtract(
                    adjustedRecovery);
            rows.set(rows.size() - 1, new EffectiveInterestRow(lastRow.periodNo(),
                    lastRow.openingNetInvestment(), adjustedInterest, lastRow.cashReceived(),
                    adjustedRecovery, adjustedClosing));
            totalInterestIncome = totalInterestIncome.add(adjustment);
        }

        BigDecimal totalInterestDifference = MoneyUtils.scale(
                totalInterestIncome.subtract(totalInterestPaid), MoneyScale.OUTPUT);
        return new EffectiveInterestTable(rows, periodIrr, totalInterestDifference);
    }

    /**
     * §9.7's 「期初余额 − 未摊销费用」, ruled down in v1 to the schedule's opening balance —
     * which the generators seed with the contract principal (ledger ruling R46; the plug-in
     * point for 初始直接费用 is the subtraction this method does not yet perform).
     */
    private static BigDecimal rowOneOpening(Installment first) {
        return MoneyUtils.scale(first.openingBalance(), MoneyScale.OUTPUT);
    }
}
