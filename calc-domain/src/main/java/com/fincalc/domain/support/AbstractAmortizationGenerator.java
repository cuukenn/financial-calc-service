package com.fincalc.domain.support;

import java.math.BigDecimal;
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

    /** One cent or more of drift is a finding; anything smaller is absorbed rounding. */
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
     * payment. The periods and the day-count convention are passed in because a periodic rate
     * cannot be derived from the nominal rate alone.
     */
    protected void prepare(CalculationContext context, List<PeriodDate> periods,
            AmortizationState state) {
    }

    /** Hook for element-level preconditions; default implementation does nothing. */
    protected void validate(LeaseElement element) {
    }

    /** Builds the period boundaries; overridable for irregular schedules. */
    protected List<PeriodDate> periods(CalculationContext context) {
        return PeriodBuilder.build(context.element(), context.dateRoll());
    }

    /**
     * Resolves the rule governing one period, or returns {@link RuleOutcome#none()} when the
     * run has no rule set.
     */
    protected RuleOutcome resolveRule(RuleSetView ruleSet, int periodNo, BigDecimal openingBalance,
            int days, BigDecimal defaultAnnualRate) {
        if (ruleSet == null) {
            return RuleOutcome.none();
        }
        RuleOutcome outcome = ruleSet.resolve(periodNo, openingBalance, days, defaultAnnualRate);
        return outcome == null ? RuleOutcome.none() : outcome;
    }

    /**
     * Annual rate applied to one period. Uses the rate resolved by the rule set when present,
     * otherwise the element's nominal rate.
     */
    protected BigDecimal annualRate(LeaseElement element, RuleOutcome outcome) {
        if (outcome == null || !outcome.matched() || outcome.annualRate() == null) {
            return element.nominalRate();
        }
        return outcome.annualRate();
    }

    /** Name recorded in the schedule header; solvers override it with their own strategy. */
    protected String solverName() {
        return DEFAULT_SOLVER;
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
        prepare(context, periods, state);

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
            RuleOutcome outcome = resolveRule(context.ruleSet(), periodNo, openingBalance, days,
                    element.nominalRate());
            BigDecimal rate = annualRate(element, outcome);
            BigDecimal periodicRate = periodRate(element, period, rate, dayCount);
            BigDecimal accruedInterest = money(openingBalance.multiply(periodicRate), rounding);
            boolean lastPeriod = index == totalPeriods - 1;

            AllocationInput input = new AllocationInput(periodNo, totalPeriods, openingBalance,
                    periodicRate, accruedInterest,
                    element.principal().subtract(cumulativePrincipal), lastPeriod, element.timing(),
                    days, element, outcome);
            Allocation allocation = allocate(input, state);
            if (allocation == null) {
                throw new IllegalStateException("allocate returned null for period " + periodNo);
            }

            BigDecimal principalDue = money(allocation.principalDue(), rounding);
            BigDecimal interestPaid = money(allocation.interestPaid(), rounding);
            BigDecimal fee = allocation.fee() == null ? money(MoneyUtils.ZERO, rounding)
                    : money(allocation.fee(), rounding);
            BigDecimal tax = allocation.tax() == null ? null : money(allocation.tax(), rounding);
            BigDecimal rentDue = money(principalDue.add(interestPaid).add(fee)
                    .add(tax == null ? MoneyUtils.ZERO : tax), rounding);
            BigDecimal closingBalance = money(openingBalance.subtract(principalDue), rounding);

            cumulativePrincipal = money(cumulativePrincipal.add(principalDue), rounding);
            cumulativeInterest = money(cumulativeInterest.add(interestPaid), rounding);
            state.cumulativePrincipal(cumulativePrincipal);
            state.cumulativeInterest(cumulativeInterest);

            installments.add(new Installment(periodNo, period.startDate(), period.endDate(), days,
                    openingBalance, principalDue, accruedInterest, interestPaid, fee, tax, rentDue,
                    closingBalance, cumulativePrincipal, cumulativeInterest, rate,
                    allocation.memo()));
            balance = closingBalance;
        }

        BigDecimal roundingDifference = checkConservation(element, installments, balance,
                rounding, issues);

        ScheduleHeader header = new ScheduleHeader(FinancialMetrics.empty(), installments.size(),
                periods.get(0).startDate(), periods.get(periods.size() - 1).endDate(),
                solverName(), roundingDifference);
        return new Schedule(header, installments, Collections.emptyList(), issues);
    }

    /**
     * Verifies that principal is conserved and that the schedule ends where the residual policy
     * says it should. Returns the absorbed rounding difference.
     */
    private BigDecimal checkConservation(LeaseElement element, List<Installment> installments,
            BigDecimal closingBalance, RoundingPolicy rounding, List<ValidationIssue> issues) {
        BigDecimal principalSum = MoneyUtils.ZERO;
        for (Installment installment : installments) {
            principalSum = principalSum.add(installment.principalDue());
        }
        BigDecimal contractPrincipal = money(element.principal(), rounding);
        BigDecimal residual = element.outstandingResidual();
        BigDecimal expectedPrincipal = money(contractPrincipal.subtract(residual), rounding);
        if (principalSum.subtract(expectedPrincipal).abs().compareTo(TOLERANCE) >= 0) {
            issues.add(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3001",
                    "FCS-CALC-3001: 本金合计 " + principalSum.toPlainString()
                            + " 与应还本金 " + expectedPrincipal.toPlainString() + " 不一致",
                    null));
        }
        BigDecimal expectedClosing = money(residual, rounding);
        BigDecimal difference = closingBalance.subtract(expectedClosing);
        if (difference.abs().compareTo(TOLERANCE) >= 0) {
            // Tagged with the last period number so the validator chain recognises this finding as
            // the same one its own tail-balance check would raise, instead of reporting it twice.
            Integer lastPeriodNo = installments.isEmpty() ? null
                    : installments.get(installments.size() - 1).periodNo();
            issues.add(new ValidationIssue(IssueLevel.ERROR, "FCS-CALC-3002",
                    "FCS-CALC-3002: 末期余额 " + closingBalance.toPlainString()
                            + " 与预期 " + expectedClosing.toPlainString() + " 不一致", lastPeriodNo));
            return money(MoneyUtils.ZERO, rounding);
        }
        return money(difference.abs(), rounding);
    }

    private static BigDecimal money(BigDecimal value, RoundingPolicy rounding) {
        return value.setScale(rounding.scale(), rounding.mode());
    }

    /** Principal and interest paid in one period. */
    public static final class Allocation {

        private final BigDecimal principalDue;
        private final BigDecimal interestPaid;
        private final BigDecimal fee;
        private final BigDecimal tax;
        private final String memo;

        public Allocation(BigDecimal principalDue, BigDecimal interestPaid) {
            this(principalDue, interestPaid, null, null, null);
        }

        public Allocation(BigDecimal principalDue, BigDecimal interestPaid, BigDecimal fee,
                BigDecimal tax, String memo) {
            this.principalDue = Objects.requireNonNull(principalDue, "principalDue");
            this.interestPaid = Objects.requireNonNull(interestPaid, "interestPaid");
            this.fee = fee;
            this.tax = tax;
            this.memo = memo;
        }

        public BigDecimal principalDue() {
            return principalDue;
        }

        public BigDecimal interestPaid() {
            return interestPaid;
        }

        /** Fee charged in this period, or {@code null} for none. */
        public BigDecimal fee() {
            return fee;
        }

        /** VAT amount, or {@code null} when the schedule does not split tax. */
        public BigDecimal tax() {
            return tax;
        }

        /** Free-form note recorded on the installment, e.g. the matched rule id. */
        public String memo() {
            return memo;
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
        private final int days;
        private final LeaseElement element;
        private final RuleOutcome ruleOutcome;

        public AllocationInput(int periodNo, int totalPeriods, BigDecimal openingBalance,
                BigDecimal periodRate, BigDecimal accruedInterest, BigDecimal remainingPrincipal,
                boolean lastPeriod, PaymentTiming paymentTiming, int days, LeaseElement element,
                RuleOutcome ruleOutcome) {
            this.periodNo = periodNo;
            this.totalPeriods = totalPeriods;
            this.openingBalance = openingBalance;
            this.periodRate = periodRate;
            this.accruedInterest = accruedInterest;
            this.remainingPrincipal = remainingPrincipal;
            this.lastPeriod = lastPeriod;
            this.paymentTiming = paymentTiming;
            this.days = days;
            this.element = element;
            this.ruleOutcome = ruleOutcome;
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

        /** Accrual days of the period. */
        public int days() {
            return days;
        }

        /** The lease element being calculated. */
        public LeaseElement element() {
            return element;
        }

        /** Rule resolved for this period; never {@code null} (use {@code matched()}). */
        public RuleOutcome ruleOutcome() {
            return ruleOutcome;
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
