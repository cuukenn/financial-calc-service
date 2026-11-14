package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.common.money.MoneyScale;
import io.github.cuukenn.fincalc.common.money.MoneyTolerance;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.metric.PeriodicRootHandoff;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.model.CashFlowType;
import io.github.cuukenn.fincalc.domain.model.EffectiveInterestRow;
import io.github.cuukenn.fincalc.domain.model.EffectiveInterestTable;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
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
     *
     * <p>Derived from {@link MoneyTolerance#MONEY_COMPARE} rather than merged with it: this is the
     * per-period share of a closing bound that is scaled by the period count, not a validator's
     * comparison tolerance, and the scaling stays here — the unit is the same cent by construction.
     */
    private static final BigDecimal CLOSING_TOLERANCE_PER_PERIOD = MoneyTolerance.MONEY_COMPARE;

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
        return calculate(schedule, periodIrr, null);
    }

    /**
     * Same chain with a terminal target the schedule itself does not carry. CAS 21 第三十八条/
     * 第三十九条 allocates finance income over the lessor's net investment, and that investment
     * is only recovered by <em>all</em> the receipts: the separately settled 留购价, the
     * guaranteed residual and the unguaranteed residual never appear in {@code rentDue} nor in the
     * schedule's closing balance, so they are passed here and the chain stops at their sum on top
     * of the schedule's own closing balance. Null keeps the schedule-only target.
     */
    public static EffectiveInterestTable calculate(Schedule schedule, BigDecimal periodIrr,
            BigDecimal closingTargetExtra) {
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
                installments.get(installments.size() - 1).closingBalance()
                        .add(MoneyUtils.zeroIfNull(closingTargetExtra)), MoneyScale.OUTPUT);
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

    /**
     * The terminal net investment the §9.7 chain must stop at (CAS 21 第三十八条 / 第三十九条):
     * the schedule's own closing balance (the ballooned 留购价) plus the money the schedule never
     * amortizes — a separately settled 留购价, the guaranteed residual, and the unguaranteed
     * residual, which the lessor recovers by keeping the asset rather than as cash. The rate
     * search prices exactly this target, which is what makes the chain close (R52's identity).
     */
    public static BigDecimal unrecoveredResiduals(LeaseElement element) {
        if (element == null) {
            return MoneyUtils.ZERO;
        }
        BigDecimal separateOption = element.residualHandling() == ResidualHandling.SEPARATE
                ? MoneyUtils.zeroIfNull(element.residualValue()) : MoneyUtils.ZERO;
        return separateOption
                .add(MoneyUtils.zeroIfNull(element.guaranteedResidual()))
                .add(MoneyUtils.zeroIfNull(element.unguaranteedResidual()));
    }

    /**
     * The net-investment periodic rate of the R52 wiring (renamed once the terminal target learned
     * about residual money): the IRR of the stream the §9.7 chain itself prices — the first opening
     * balance at t0, the rent legs, and the terminal net investment re-dated to the last rent's
     * index when nonzero.
     *
     * <p><b>One solve per stream (AGENTS.md N-05).</b> When that stream is a flow set some solve
     * of the run already priced — the §9.4 event table {@code IRR_PERIOD} was solved over, or the
     * implicit-rate measure chain's table-plus-unguaranteed-residual set — the run reads the
     * <em>unrounded</em> root off the {@link PeriodicRootHandoff} ledger instead of solving the same
     * problem twice. The raw root and not the published scale-10 {@code IRR_PERIOD} is what keeps
     * every §9.7 row cent-for-cent (AGENTS.md N-04: the 0.01 元容差 is a validation tolerance,
     * never a licence to price a chain with a rounded rate). Where the streams differ — a separately
     * settled 留购价, a guaranteed residual listed on top of the balloon, the unguaranteed residual
     * CAS 21 第三十八条 prices into the net investment while §9.4 keeps the event table cash-only,
     * an upfront fee or a deposit leg — the chain solves its own root: a different stream has a
     * different root, and that stated caliber difference is the only reason a run pays for a second
     * solve.
     */
    public static BigDecimal netInvestmentPeriodIrr(Schedule schedule, BigDecimal residualExtra,
            PeriodicRootHandoff rootHandoff) {
        List<Installment> installments = schedule.installments();
        if (installments.isEmpty()) {
            return null;
        }
        List<CashFlow> stream = new ArrayList<>(installments.size() + 1);
        Installment first = installments.get(0);
        stream.add(new CashFlow(0, first.startDate(), first.openingBalance(), CashFlowType.PRINCIPAL,
                CashFlowDirection.OUTFLOW));
        for (Installment installment : installments) {
            BigDecimal rent = installment.rentDue();
            // A zero rent carries no cash and a negative one is a broken schedule the validators
            // already flag; neither may enter the root search.
            if (rent != null && rent.signum() > 0) {
                stream.add(new CashFlow(installment.periodNo(), installment.endDate(), rent,
                        CashFlowType.RENT, CashFlowDirection.INFLOW));
            }
        }
        Installment last = installments.get(installments.size() - 1);
        BigDecimal terminal = MoneyUtils.scale(
                last.closingBalance().add(MoneyUtils.zeroIfNull(residualExtra)), MoneyScale.OUTPUT);
        if (terminal.signum() != 0) {
            stream.add(new CashFlow(last.periodNo(), last.endDate(), terminal,
                    CashFlowType.RESIDUAL, CashFlowDirection.INFLOW));
        }

        BigDecimal solvedOverRun = rootHandoff.rootFor(stream);
        if (solvedOverRun != null) {
            // The stream this chain prices is the very stream some solve of this run — the §9.5
            // metric line's IRR_PERIOD, or the implicit-rate measure chain — already solved, or its
            // exact direction mirror (R103): same discount indices, same signed amounts, so it has
            // the same root, and solving it again is the duplicate AGENTS.md N-05 forbids. Read
            // the ledger's unrounded root, not the published scale-10 IRR_PERIOD: substituting the
            // rounded rate would move cents along the chain's rows (AGENTS.md N-04).
            return solvedOverRun;
        }
        // A stated second solve, not a silent one. This stream differs from the §9.4 event table the
        // run's IRR_PERIOD is a root of whenever the deal carries money the event table does not list
        // in the same place: an upfront fee or deposit leg, a separately settled 留购价, a guaranteed
        // residual on top of the balloon, or — CAS 21 第三十八条 — the unguaranteed residual, which is
        // part of the lessor's net investment the chain must unwind but never becomes cash (§9.4 keeps
        // the event table cash-only). Different stream, therefore a different root, therefore its own
        // solve: that is the caliber difference, and it is the only reason this run pays for two.
        return IrrCalculator.periodIrr(stream);
    }
}
