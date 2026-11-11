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
 * <p>Residual money is listed exactly once, following how the schedule actually settles it
 * (ledger ruling R28): under {@link ResidualHandling#SEPARATE} the option price is an extra
 * maturity inflow while the rents amortize the full principal; under
 * {@link ResidualHandling#BALLOON_IN_RENT} the schedule is required to end with the residual as
 * its closing balance (design document §9.6, {@code TAIL_BALANCE}), so that outstanding amount —
 * not the raw residual — is appended as the maturity settlement. Dropping it would erase money
 * the lessor receives and silently misprice every yield metric.
 *
 * <p>Known v1 gaps: a {@link FeeChargeMode#DEDUCT_FROM_PRINCIPAL} fee or a deposit netted against
 * the t0 principal would have no leg because v1 does not net anything — the {@code FeeAllocator}
 * extension point arrives with the fee work (Plan 2); until then such contracts price the gross
 * principal. Rent legs land on {@code installment.endDate()} because v1 schedules are
 * {@code IN_ARREARS} only.
 */
public final class StandardCashFlowBuilder implements CashFlowBuilder {

    /** Fixed order of same-date events; stable IRR input requires a total order. */
    private static final Map<CashFlowType, Integer> SAME_DAY_ORDER;

    static {
        Map<CashFlowType, Integer> order = new EnumMap<>(CashFlowType.class);
        order.put(CashFlowType.PRINCIPAL, 0);
        order.put(CashFlowType.FEE, 1);
        order.put(CashFlowType.DEPOSIT, 2);
        order.put(CashFlowType.RENT, 3);
        order.put(CashFlowType.RESIDUAL, 4);
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
            BigDecimal deposit = money(element.deposit(), rounding);
            addFlow(flows, t0, deposit, CashFlowType.DEPOSIT, CashFlowDirection.INFLOW);
            addFlow(flows, maturity, deposit, CashFlowType.DEPOSIT, CashFlowDirection.OUTFLOW);
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
     * A deposit participates only under the basis that asks for it; v1 deposits are refundable
     * only, so they never net against the t0 principal.
     */
    private boolean depositIncluded(CalculationOptions options, LeaseElement element) {
        return options.cashFlowBasis() == CashFlowBasis.INCLUDE_DEPOSIT
                && element.deposit() != null
                && element.deposit().signum() > 0;
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

    /** SEPARATE lists the option price; BALLOON lists whatever the schedule actually left outstanding. */
    private void addClosingResidual(List<CashFlow> flows, LeaseElement element,
            RoundingPolicy rounding, List<Installment> installments, LocalDate maturity) {
        if (maturity == null) {
            return;
        }
        if (element.residualHandling() == ResidualHandling.SEPARATE) {
            addFlow(flows, maturity, money(element.residualValue(), rounding),
                    CashFlowType.RESIDUAL, CashFlowDirection.INFLOW);
            return;
        }
        if (element.residualHandling() == ResidualHandling.BALLOON_IN_RENT) {
            BigDecimal outstanding = installments.get(installments.size() - 1).closingBalance();
            addFlow(flows, maturity, outstanding, CashFlowType.RESIDUAL, CashFlowDirection.INFLOW);
        }
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
