package com.fincalc.domain.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.metric.FinancialMetrics;
import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.IssueLevel;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.PeriodDate;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.model.RuleOutcome;
import com.fincalc.domain.model.Schedule;
import com.fincalc.domain.model.ScheduleHeader;
import com.fincalc.domain.model.ValidationIssue;
import com.fincalc.domain.port.DayCountConvention;
import com.fincalc.domain.port.RuleSetView;
import com.fincalc.domain.port.ScheduleGenerator;

/**
 * Template of a closed-form amortization schedule: the base class owns period iteration,
 * interest accrual, installment assembly and the conservation check; subclasses decide how
 * principal is allocated through {@link #allocate(AllocationInput, AmortizationState)}.
 *
 * <p>Generators are stateless and therefore safe to share: per-run state lives in the
 * {@link AmortizationState} created inside {@link #generate(CalculationContext)}.
 */
public abstract class AbstractAmortizationGenerator implements ScheduleGenerator {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final String DEFAULT_SOLVER = "CLOSED_FORM";

    /**
     * Allocates principal and the paid part of accrued interest for one period.
     *
     * @param input period facts and the contract-level remaining principal
     * @param state mutable run state shared across periods (deferred interest, PMT, cumulatives)
     */
    protected abstract Allocation allocate(AllocationInput input, AmortizationState state);

    /**
     * Hook for one-off parameters that must be computed before the loop, typically the level
     * payment. Default implementation does nothing.
     */
    protected void prepare(LeaseElement element, AmortizationState state) {
    }

    /** Hook for element-level preconditions; default implementation does nothing. */
    protected void validate(LeaseElement element) {
    }

    /** Builds the period boundaries; overridable for irregular schedules. */
    protected List<PeriodDate> periods(CalculationContext context) {
        return PeriodBuilder.build(context.element(), context.dateRoll());
    }

    /**
     * Annual rate applied to one period. Defaults to the rate resolved by the rule set, or the
     * element's nominal rate when no rule matches.
     */
    protected BigDecimal annualRate(LeaseElement element, RuleSetView ruleSet, int periodNo,
            BigDecimal openingBalance, int days) {
        if (ruleSet == null) {
            return element.nominalRate();
        }
        RuleOutcome outcome = ruleSet.resolve(periodNo, openingBalance, days, element.nominalRate());
        if (outcome == null || !outcome.matched() || outcome.annualRate() == null) {
            return element.nominalRate();
        }
        return outcome.annualRate();
    }

    /**
     * Periodic rate of one period: the annual rate scaled by the day-count year fraction.
     * Overridable for conventions that do not use an annual-rate times fraction model.
     */
    protected BigDecimal periodRate(LeaseElement element, PeriodDate period, BigDecimal annualRate,
            DayCountConvention dayCount) {
        return annualRate.multiply(dayCount.yearFraction(period.startDate(), period.endDate()));
    }

    @Override
    public Schedule generate(CalculationContext context) {
        Objects.requireNonNull(context, "context");
        LeaseElement element = context.element();
        validate(element);

        List<PeriodDate> periods = periods(context);
        if (periods.isEmpty()) {
            throw new IllegalStateException("period builder returned no periods");
        }

        RoundingPolicy rounding = element.rounding();
        DayCountConvention dayCount = context.dayCount();
        AmortizationState state = new AmortizationState();
        prepare(element, state);

        List<Installment> installments = new ArrayList<>(periods.size());
        List<ValidationIssue> issues = new ArrayList<>();
        BigDecimal balance = money(element.principal(), rounding);
        BigDecimal cumulativePrincipal = money(MoneyUtils.ZERO, rounding);
        BigDecimal cumulativeInterest = money(MoneyUtils.ZERO, rounding);
        int totalPeriods = periods.size();

        for (int index = 0; index < totalPeriods; index++) {
            PeriodDate period = periods.get(index);
            int periodNo = index + 1;
            int days = dayCount.days(period.startDate(), period.endDate());
            BigDecimal openingBalance = balance;
            BigDecimal rate = annualRate(element, context.ruleSet(), periodNo, openingBalance, days);
            BigDecimal periodicRate = periodRate(element, period, rate, dayCount);
            BigDecimal accruedInterest = money(openingBalance.multiply(periodicRate), rounding);
            boolean lastPeriod = index == totalPeriods - 1;

            AllocationInput input = new AllocationInput(periodNo, totalPeriods, openingBalance,
                    periodicRate, accruedInterest,
                    element.principal().subtract(cumulativePrincipal), lastPeriod, element.timing());
            Allocation allocation = allocate(input, state);
            if (allocation == null) {
                throw new IllegalStateException("allocate returned null for period " + periodNo);
            }

            BigDecimal principalDue = money(allocation.principalDue(), rounding);
            BigDecimal interestPaid = money(allocation.interestPaid(), rounding);
            BigDecimal fee = money(MoneyUtils.ZERO, rounding);
            BigDecimal tax = money(MoneyUtils.ZERO, rounding);
            BigDecimal rentDue = money(principalDue.add(interestPaid).add(fee).add(tax), rounding);
            BigDecimal closingBalance = money(openingBalance.subtract(principalDue), rounding);

            cumulativePrincipal = money(cumulativePrincipal.add(principalDue), rounding);
            cumulativeInterest = money(cumulativeInterest.add(interestPaid), rounding);

            installments.add(new Installment(periodNo, period.startDate(), period.endDate(), days,
                    openingBalance, principalDue, accruedInterest, interestPaid, fee, tax, rentDue,
                    closingBalance, cumulativePrincipal, cumulativeInterest, rate, null));
            balance = closingBalance;
        }

        checkConservation(element, installments, balance, rounding, issues);

        ScheduleHeader header = new ScheduleHeader(FinancialMetrics.empty(), installments.size(),
                periods.get(0).startDate(), periods.get(periods.size() - 1).endDate(),
                DEFAULT_SOLVER, money(MoneyUtils.ZERO, rounding));
        return new Schedule(header, installments, Collections.emptyList(), issues);
    }

    private void checkConservation(LeaseElement element, List<Installment> installments,
            BigDecimal closingBalance, RoundingPolicy rounding, List<ValidationIssue> issues) {
        BigDecimal principalSum = MoneyUtils.ZERO;
        for (Installment installment : installments) {
            principalSum = principalSum.add(installment.principalDue());
        }
        if (principalSum.subtract(element.principal()).abs().compareTo(TOLERANCE) > 0) {
            issues.add(new ValidationIssue(IssueLevel.ERROR, "FCS-DOM-2002",
                    "FCS-DOM-2002: 本金合计 " + principalSum.toPlainString()
                            + " 与合同本金 " + element.principal().toPlainString() + " 不一致",
                    null));
        }
        if (closingBalance.abs().compareTo(TOLERANCE) > 0) {
            issues.add(new ValidationIssue(IssueLevel.ERROR, "FCS-DOM-2002",
                    "FCS-DOM-2002: 末期余额 " + closingBalance.toPlainString() + " 未归零", null));
        }
    }

    private static BigDecimal money(BigDecimal value, RoundingPolicy rounding) {
        return value.setScale(rounding.scale(), rounding.mode());
    }

    /** Principal and interest paid in one period. */
    public static final class Allocation {

        private final BigDecimal principalDue;
        private final BigDecimal interestPaid;

        public Allocation(BigDecimal principalDue, BigDecimal interestPaid) {
            this.principalDue = principalDue;
            this.interestPaid = interestPaid;
        }

        public BigDecimal principalDue() {
            return principalDue;
        }

        public BigDecimal interestPaid() {
            return interestPaid;
        }
    }

    /** Facts passed to {@link #allocate(AllocationInput, AmortizationState)} for one period. */
    public static final class AllocationInput {

        private final int periodNo;
        private final int totalPeriods;
        private final BigDecimal openingBalance;
        private final BigDecimal periodRate;
        private final BigDecimal accruedInterest;
        private final BigDecimal remainingPrincipal;
        private final boolean lastPeriod;
        private final PaymentTiming paymentTiming;

        public AllocationInput(int periodNo, int totalPeriods, BigDecimal openingBalance,
                BigDecimal periodRate, BigDecimal accruedInterest, BigDecimal remainingPrincipal,
                boolean lastPeriod, PaymentTiming paymentTiming) {
            this.periodNo = periodNo;
            this.totalPeriods = totalPeriods;
            this.openingBalance = openingBalance;
            this.periodRate = periodRate;
            this.accruedInterest = accruedInterest;
            this.remainingPrincipal = remainingPrincipal;
            this.lastPeriod = lastPeriod;
            this.paymentTiming = paymentTiming;
        }

        public int periodNo() {
            return periodNo;
        }

        public int totalPeriods() {
            return totalPeriods;
        }

        public BigDecimal openingBalance() {
            return openingBalance;
        }

        public BigDecimal periodRate() {
            return periodRate;
        }

        public BigDecimal accruedInterest() {
            return accruedInterest;
        }

        /** Contract principal not yet repaid before this period. */
        public BigDecimal remainingPrincipal() {
            return remainingPrincipal;
        }

        public boolean lastPeriod() {
            return lastPeriod;
        }

        public PaymentTiming paymentTiming() {
            return paymentTiming;
        }
    }

    /** Mutable per-run state shared by all periods of one {@link #generate} call. */
    public static final class AmortizationState {

        private BigDecimal deferredInterest = MoneyUtils.ZERO;
        private BigDecimal cumulativePrincipal = MoneyUtils.ZERO;
        private BigDecimal cumulativeInterest = MoneyUtils.ZERO;
        private BigDecimal pmt;

        public BigDecimal deferredInterest() {
            return deferredInterest;
        }

        public void deferredInterest(BigDecimal deferredInterest) {
            this.deferredInterest = deferredInterest;
        }

        public BigDecimal cumulativePrincipal() {
            return cumulativePrincipal;
        }

        public void cumulativePrincipal(BigDecimal cumulativePrincipal) {
            this.cumulativePrincipal = cumulativePrincipal;
        }

        public BigDecimal cumulativeInterest() {
            return cumulativeInterest;
        }

        public void cumulativeInterest(BigDecimal cumulativeInterest) {
            this.cumulativeInterest = cumulativeInterest;
        }

        /** Level payment computed by {@link #prepare}; {@code null} until a subclass sets it. */
        public BigDecimal pmt() {
            return pmt;
        }

        public void pmt(BigDecimal pmt) {
            this.pmt = pmt;
        }
    }
}
