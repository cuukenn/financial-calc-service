package com.fincalc.domain.cashflow;

import com.fincalc.common.error.BusinessException;
import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.calc.CalcErrorCode;
import com.fincalc.domain.model.CalculationOptions;
import com.fincalc.domain.model.CashFlow;
import com.fincalc.domain.model.CashFlowBasis;
import com.fincalc.domain.model.CashFlowDirection;
import com.fincalc.domain.model.CashFlowPerspective;
import com.fincalc.domain.model.CashFlowType;
import com.fincalc.domain.model.FeeChargeMode;
import com.fincalc.domain.model.FeeItem;
import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.ResidualHandling;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.model.Schedule;
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
 * Turns a finished schedule into the ordered cash flow list every financial metric consumes
 * (design document §9.4), on the lessor books by default: principal out at t0, rents in at the
 * end of every period, then the closing events.
 *
 * <p>Deduplication rules the numbers depend on: {@code tax} already inside {@code rentDue} is
 * never listed again; a {@link ResidualHandling#BALLOON_IN_RENT} residual is already in the final
 * rent; zero-amount events are dropped. Flows are ordered by date, then by the fixed same-day
 * type order {@code PRINCIPAL → FEE → DEPOSIT → RENT → RESIDUAL}, and renumbered from 0 — the
 * order must be deterministic or the same input would price differently.
 */
public final class CashFlowBuilder {

    /** Fixed order of same-date events; stable IRR input requires a total order. */
    private static final Map<CashFlowType, Integer> SAME_DAY_ORDER;

    static {
        Map<CashFlowType, Integer> order = new EnumMap<>(CashFlowType.class);
        order.put(CashFlowType.PRINCIPAL, 0);
        order.put(CashFlowType.FEE, 1);
        order.put(CashFlowType.DEPOSIT, 2);
        order.put(CashFlowType.RENT, 3);
        order.put(CashFlowType.RESIDUAL, 4);
        SAME_DAY_ORDER = order;
    }

    private CashFlowBuilder() {
    }

    /** The lessor view (§9.4 default) with the run's cash flow basis. */
    public static List<CashFlow> build(Schedule schedule, LeaseElement element,
            CalculationOptions options) {
        return build(schedule, element, options, CashFlowPerspective.LESSOR);
    }

    /**
     * Cash flows of a finished schedule, seen from {@code perspective}. The lessee view mirrors
     * every direction; dates, types, amounts and order are identical.
     *
     * @throws BusinessException when the basis is {@link CashFlowBasis#VAT_SPLIT}, which v1
     *         rejects ({@code FCS-CALC-3005}) instead of silently degrading
     */
    public static List<CashFlow> build(Schedule schedule, LeaseElement element,
            CalculationOptions options, CashFlowPerspective perspective) {
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
        LocalDate maturity = installments.isEmpty() ? null
                : installments.get(installments.size() - 1).endDate();

        List<CashFlow> flows = new ArrayList<>();
        addFlow(flows, t0, money(element.principal(), rounding), CashFlowType.PRINCIPAL,
                CashFlowDirection.OUTFLOW);
        addUpfrontFees(flows, element, rounding, t0);
        if (depositIncluded(options, element)) {
            BigDecimal deposit = money(element.deposit(), rounding);
            addFlow(flows, t0, deposit, CashFlowType.DEPOSIT, CashFlowDirection.INFLOW);
            if (maturity != null) {
                addFlow(flows, maturity, deposit, CashFlowType.DEPOSIT, CashFlowDirection.OUTFLOW);
            }
        }
        for (Installment installment : installments) {
            // tax is already part of rentDue and fee events of AMORTIZED fees ride inside it;
            // listing them again would count them twice.
            addFlow(flows, installment.endDate(), installment.rentDue(), CashFlowType.RENT,
                    CashFlowDirection.INFLOW);
        }
        if (element.residualHandling() == ResidualHandling.SEPARATE && maturity != null) {
            addFlow(flows, maturity, money(element.residualValue(), rounding),
                    CashFlowType.RESIDUAL, CashFlowDirection.INFLOW);
        }

        return sorted(flows, perspective);
    }

    /**
     * A deposit participates only under the basis that asks for it; v1 deposits are refundable
     * only, so they never net against the t0 principal.
     */
    private static boolean depositIncluded(CalculationOptions options, LeaseElement element) {
        return options.cashFlowBasis() == CashFlowBasis.INCLUDE_DEPOSIT
                && element.deposit() != null
                && element.deposit().signum() > 0;
    }

    private static void addUpfrontFees(List<CashFlow> flows, LeaseElement element,
            RoundingPolicy rounding, LocalDate t0) {
        BigDecimal total = MoneyUtils.ZERO;
        for (FeeItem fee : element.fees()) {
            if (fee.chargeMode() == FeeChargeMode.UPFRONT && fee.includeInIrr()) {
                total = total.add(fee.amount());
            }
        }
        addFlow(flows, t0, money(total, rounding), CashFlowType.FEE, CashFlowDirection.INFLOW);
    }

    /** Zero-amount events are dropped: they would add noise and break the positive-amount rule. */
    private static void addFlow(List<CashFlow> flows, LocalDate date, BigDecimal amount,
            CashFlowType type, CashFlowDirection direction) {
        if (amount == null || amount.signum() == 0) {
            return;
        }
        flows.add(new CashFlow(flows.size(), date, amount, type, direction));
    }

    private static List<CashFlow> sorted(List<CashFlow> flows, CashFlowPerspective perspective) {
        Collections.sort(flows, Comparator.comparing(CashFlow::date)
                .thenComparing(flow -> SAME_DAY_ORDER.get(flow.type()))
                .thenComparing(CashFlow::sequence));

        List<CashFlow> ordered = new ArrayList<>(flows.size());
        for (int index = 0; index < flows.size(); index++) {
            CashFlow flow = flows.get(index);
            ordered.add(new CashFlow(index, flow.date(), flow.amount(), flow.type(),
                    flip(flow.direction(), perspective)));
        }
        return Collections.unmodifiableList(ordered);
    }

    private static CashFlowDirection flip(CashFlowDirection direction,
            CashFlowPerspective perspective) {
        if (perspective == CashFlowPerspective.LESSOR) {
            return direction;
        }
        return direction == CashFlowDirection.INFLOW
                ? CashFlowDirection.OUTFLOW : CashFlowDirection.INFLOW;
    }

    private static BigDecimal money(BigDecimal value, RoundingPolicy rounding) {
        return MoneyUtils.zeroIfNull(value).setScale(rounding.scale(), rounding.mode());
    }
}
