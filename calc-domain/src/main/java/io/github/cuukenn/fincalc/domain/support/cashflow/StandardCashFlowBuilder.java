package io.github.cuukenn.fincalc.domain.support.cashflow;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowBasis;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.model.CashFlowType;
import io.github.cuukenn.fincalc.domain.model.DepositSettlement;
import io.github.cuukenn.fincalc.domain.model.DepositTiming;
import io.github.cuukenn.fincalc.domain.model.FeeChargeMode;
import io.github.cuukenn.fincalc.domain.model.FeeItem;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.port.CashFlowBuilder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The standard §9.4 event table: principal out at t0, upfront fees in at t0, the deposit cycle
 * under the deposit basis, one rent leg per period on the schedule's own payment dates, and the
 * closing settlement leg.
 *
 * <p>Terminal receipts follow how the schedule actually settles them (ledger ruling R28, CAS 21
 * 第三十七条): under {@link ResidualHandling#SEPARATE} the purchase option price is an extra
 * maturity inflow while the rents amortize the full principal; under
 * {@link ResidualHandling#BALLOON_IN_RENT} the schedule is required to end with that price as its
 * closing balance (design document §9.6, {@code TAIL_BALANCE}), so the outstanding amount — not the
 * raw input — is appended as the maturity settlement. The guaranteed residual is added on top in
 * both modes because it is a separate receipt line. Dropping either would erase money the lessor
 * receives and silently misprice every yield metric.
 *
 * <p>Known v1 gaps: a {@link FeeChargeMode#DEDUCT_FROM_PRINCIPAL} fee has no leg because v1 does
 * not net fees — the {@code FeeAllocator} extension point arrives with the fee work (Plan 2);
 * until then such contracts price the gross principal. A deposit that offsets the principal is
 * netted the other way round (form-field-gaps ticket 03): the schedule amortizes
 * {@link LeaseElement#measuredPrincipal()} and the event table keeps both t0 legs explicit.
 * Rent legs land on {@code installment.endDate()} because v1 schedules are {@code IN_ARREARS}
 * only.
 */
public final class StandardCashFlowBuilder implements CashFlowBuilder {

    /** Fixed order of same-date events; stable IRR input requires a total order. */
    private static final Map<CashFlowType, Integer> SAME_DAY_ORDER;

    static {
        Map<CashFlowType, Integer> order = new EnumMap<>(CashFlowType.class);
        order.put(CashFlowType.PRINCIPAL, 0);
        order.put(CashFlowType.FEE, 1);
        order.put(CashFlowType.DEPOSIT, 2);
        order.put(CashFlowType.DEPOSIT_INTEREST, 3);
        order.put(CashFlowType.RENT, 4);
        order.put(CashFlowType.RESIDUAL, 5);
        SAME_DAY_ORDER = Collections.unmodifiableMap(order);
    }

    @Override
    public String code() {
        return "STANDARD_EVENT_TABLE";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<CashFlow> build(Schedule schedule, LeaseElement element, CalculationOptions options,
            CashFlowPerspective perspective) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(perspective, "perspective");
        if (options.cashFlowBasis() == CashFlowBasis.VAT_SPLIT) {
            throw new BusinessException(CalcErrorCode.VAT_SPLIT_NOT_SUPPORTED);
        }

        RoundingPolicy rounding = element.rounding();
        LocalDate t0 = element.commenceDate();
        List<Installment> installments = schedule.installments();
        boolean settled = !installments.isEmpty();
        LocalDate maturity = settled ? installments.get(installments.size() - 1).endDate() : null;

        List<CashFlow> flows = new ArrayList<>();
        BigDecimal opening = element.assetValue() == null
                ? element.principal()
                : element.assetValue().add(element.initialDirectCosts() == null
                        ? BigDecimal.ZERO : element.initialDirectCosts());
        addFlow(flows, t0, money(opening, rounding), CashFlowType.PRINCIPAL,
                CashFlowDirection.OUTFLOW);
        if (element.downPayment() != null && element.downPayment().signum() > 0) {
            addFlow(flows, t0, money(element.downPayment(), rounding),
                    CashFlowType.PRINCIPAL, CashFlowDirection.INFLOW);
        }
        addUpfrontFees(flows, element, rounding, t0);
        if (settled && depositIncluded(options, element)) {
            addDepositLegs(flows, element, rounding, t0, installments, maturity);
        }
        for (Installment installment : installments) {
            // tax is already part of rentDue. v1 consumes only UPFRONT fees (see
            // addUpfrontFees): AMORTIZED fee folding into rentDue is NOT implemented, and it
            // MUST NOT be applied to EXPLICIT_RENT schedules, whose rentDue is the verbatim
            // input rent (Plan 2's FeeAllocator work must respect this constraint).
            addFlow(flows, installment.endDate(), installment.rentDue(), CashFlowType.RENT,
                    CashFlowDirection.INFLOW);
        }
        addClosingResidual(flows, element, rounding, installments, maturity);

        return sorted(flows, perspective);
    }

    /**
     * A deposit participates only under the basis that asks for it. The legs themselves are shaped
     * by the element's 收取时点 / 退还抵扣规则 (AGENTS.md §3.1 第 11 条; calibers documented on
     * {@link DepositSettlement}); the event table keeps the deposit money explicit — under
     * {@link DepositSettlement#OFFSET_PRINCIPAL} the two t0 legs (gross principal out, deposit in)
     * net to the {@link LeaseElement#measuredPrincipal()} the schedule amortizes, and under the
     * refund/offset-final-rent settlements the deposit (plus its interest when carried) is its own
     * maturity rows that never fold into 租金 (M-03/M-04).
     */
    private boolean depositIncluded(CalculationOptions options, LeaseElement element) {
        return options.cashFlowBasis() == CashFlowBasis.INCLUDE_DEPOSIT
                && element.deposit() != null
                && element.deposit().signum() > 0;
    }

    /**
     * The deposit legs, per the element's 收取时点 / 退还抵扣规则. Collection lands on t0 under
     * {@link DepositTiming#UPFRONT} and on the first rent's payment date under
     * {@link DepositTiming#WITH_FIRST_RENT} — the same date the rent rows use, so same-day
     * compression keeps the deposit and the first rent on one discount index.
     *
     * <p>Settlement: {@link DepositSettlement#REFUND} and {@link DepositSettlement#OFFSET_FINAL_RENT}
     * return the deposit at maturity as its own rows — offsetting the final rent is the same cash
     * pair as a refund dated at maturity, so the schedule's rent column stays the amortization rent
     * and the offset lives in the deposit rows, never netted into 租金;
     * {@link DepositSettlement#OFFSET_PRINCIPAL} has no settlement leg because the deposit is
     * consumed at collection.
     *
     * <p>Interest (P-03/P-06): the deposit earns its element rate over the days it actually
     * occupies — ACT/360, 算头不算尾, simple — and the interest is its own maturity outflow, never
     * folded into rent or the deposit principal leg.
     */
    private void addDepositLegs(List<CashFlow> flows, LeaseElement element,
            RoundingPolicy rounding, LocalDate t0, List<Installment> installments,
            LocalDate maturity) {
        BigDecimal deposit = money(element.deposit(), rounding);
        LocalDate collected = element.depositTiming() == DepositTiming.WITH_FIRST_RENT
                ? installments.get(0).endDate() : t0;
        addFlow(flows, collected, deposit, CashFlowType.DEPOSIT, CashFlowDirection.INFLOW);
        if (element.depositSettlement() == DepositSettlement.OFFSET_PRINCIPAL) {
            return;
        }
        addFlow(flows, maturity, depositInterest(element, rounding, collected, maturity),
                CashFlowType.DEPOSIT_INTEREST, CashFlowDirection.OUTFLOW);
        addFlow(flows, maturity, deposit, CashFlowType.DEPOSIT, CashFlowDirection.OUTFLOW);
    }

    /**
     * Deposit interest at the element's rate over the days the deposit actually occupies:
     * 保证金 × 年利率 × 实际占用天数 ÷ 360 (P-03 日利率、算头不算尾、单利), rounded to the
     * element's output scale (P-05/P-06). A same-day settlement accrues zero days and no row; a
     * settlement cannot precede collection because maturity is the schedule's last end date and
     * the collection date is t0 or the first rent's date.
     */
    private BigDecimal depositInterest(LeaseElement element, RoundingPolicy rounding,
            LocalDate collected, LocalDate settled) {
        BigDecimal rate = element.depositInterestRate();
        if (rate == null || !settled.isAfter(collected)) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(collected, settled);
        return element.deposit()
                .multiply(rate)
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(360), rounding.scale(), rounding.mode());
    }

    private void addUpfrontFees(List<CashFlow> flows, LeaseElement element,
            RoundingPolicy rounding, LocalDate t0) {
        BigDecimal total = MoneyUtils.ZERO;
        for (FeeItem fee : element.fees()) {
            if (fee.chargeMode() == FeeChargeMode.UPFRONT && fee.includeInIrr()) {
                total = total.add(fee.amount());
            }
        }
        addFlow(flows, t0, money(total, rounding), CashFlowType.FEE, CashFlowDirection.INFLOW);
    }

    /**
     * Terminal receipts, listed once each (CAS 21 第三十七条 租赁收款额):
     *
     * <ul>
     *   <li><b>购买选择权行权价 / 留购价</b> — under {@link ResidualHandling#BALLOON_IN_RENT} it *is*
     *       the schedule's closing balance (the rents amortise principal minus the option price),
     *       so the outstanding amount is listed; under {@link ResidualHandling#SEPARATE} the rents
     *       amortise the full principal and the option price is listed as an extra maturity
     *       receipt.</li>
     *   <li><b>担保余值</b> — always an extra maturity receipt on top of the option price; it is a
     *       separate CAS 21 receipt line, not another name for the same money.</li>
     * </ul>
     *
     * <p>The unguaranteed residual is deliberately absent: it never becomes cash. It enters only
     * the lease implicit-rate equation.
     */
    private void addClosingResidual(List<CashFlow> flows, LeaseElement element,
            RoundingPolicy rounding, List<Installment> installments, LocalDate maturity) {
        if (maturity == null) {
            return;
        }
        if (element.residualHandling() == ResidualHandling.BALLOON_IN_RENT) {
            BigDecimal outstanding = installments.get(installments.size() - 1).closingBalance();
            addFlow(flows, maturity, outstanding, CashFlowType.RESIDUAL, CashFlowDirection.INFLOW);
        } else {
            addFlow(flows, maturity, money(element.residualValue(), rounding),
                    CashFlowType.RESIDUAL, CashFlowDirection.INFLOW);
        }
        // CAS 21 担保余值: a distinct terminal receipt, so it never replaces the option price.
        addFlow(flows, maturity, money(element.guaranteedResidual(), rounding),
                CashFlowType.RESIDUAL, CashFlowDirection.INFLOW);
    }

    /**
     * Zero-amount events are dropped; a negative one means the schedule itself is broken —
     * amounts are positive by invariant and the sign belongs to {@link CashFlowDirection}.
     */
    private void addFlow(List<CashFlow> flows, LocalDate date, BigDecimal amount,
            CashFlowType type, CashFlowDirection direction) {
        if (amount == null || amount.signum() == 0) {
            return;
        }
        if (amount.signum() < 0) {
            throw new IllegalStateException("negative cash flow amount for " + type
                    + " on " + date + ": " + amount.toPlainString());
        }
        flows.add(new CashFlow(flows.size(), date, amount, type, direction));
    }

    private List<CashFlow> sorted(List<CashFlow> flows, CashFlowPerspective perspective) {
        Collections.sort(flows, Comparator.comparing(CashFlow::date)
                .thenComparing(flow -> SAME_DAY_ORDER.getOrDefault(flow.type(), Integer.MAX_VALUE))
                .thenComparing(CashFlow::sequence));

        List<CashFlow> ordered = new ArrayList<>(flows.size());
        int sequence = -1;
        LocalDate previousDate = null;
        for (CashFlow flow : flows) {
            // Same-day flows share one discount index: npv() discounts by sequence, so two
            // events on the same date must not be one period apart.
            if (!flow.date().equals(previousDate)) {
                sequence++;
                previousDate = flow.date();
            }
            ordered.add(new CashFlow(sequence, flow.date(), flow.amount(), flow.type(),
                    flip(flow.direction(), perspective)));
        }
        return Collections.unmodifiableList(ordered);
    }

    private CashFlowDirection flip(CashFlowDirection direction, CashFlowPerspective perspective) {
        if (perspective == CashFlowPerspective.LESSOR) {
            return direction;
        }
        return direction == CashFlowDirection.INFLOW
                ? CashFlowDirection.OUTFLOW : CashFlowDirection.INFLOW;
    }

    private BigDecimal money(BigDecimal value, RoundingPolicy rounding) {
        return MoneyUtils.zeroIfNull(value).setScale(rounding.scale(), rounding.mode());
    }
}
