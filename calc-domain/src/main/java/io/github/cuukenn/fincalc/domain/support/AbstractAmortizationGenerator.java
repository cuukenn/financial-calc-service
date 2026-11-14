package io.github.cuukenn.fincalc.domain.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.money.MoneyTolerance;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.model.DomainErrorCode;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.RuleOutcome;
import io.github.cuukenn.fincalc.domain.model.TailAdjustPolicy;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.support.FirstPeriodStubSplit;
import io.github.cuukenn.fincalc.domain.model.ScheduleHeader;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import io.github.cuukenn.fincalc.domain.port.RuleSetView;
import io.github.cuukenn.fincalc.domain.port.ScheduleGenerator;

/**
 * Template of a closed-form amortization schedule: the base class owns period iteration,
 * interest accrual, installment assembly and the conservation check; subclasses decide how
 * principal is allocated through {@link #allocate(AllocationInput, AmortizationState)}.
 *
 * <p>Generators are stateless and therefore safe to share: per-run state lives in the
 * {@link AmortizationState} created inside {@link #generate(CalculationContext)}.
 */
public abstract class AbstractAmortizationGenerator implements ScheduleGenerator {

    /**
     * One cent or more of drift is a finding; anything smaller is absorbed rounding. The value is
     * single-sourced in {@link MoneyTolerance} so the generator and the validator chain judge on the
     * same line.
     */
    private static final BigDecimal TOLERANCE = MoneyTolerance.MONEY_COMPARE;
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
     * Resolves the rule governing one period through the {@link RuleSetView} port's 4-arg
     * resolve, or returns {@link RuleOutcome#none()} when the run has no rule set. This is the
     * R70 period-rate resolution order for the built-in generators: a rule hit carries the
     * period's annual rate (read back in {@link #annualRate}); an unmatched period falls back to
     * the element's nominal rate there.
     *
     * <p>Honest-no-match posture (Task 26 semantics, unchanged here): the 4-arg port resolve
     * skips conditioned rows — evaluating a condition needs the per-period
     * {@code EvalContext}, which only calc-rule can build. For the built-in generators a period
     * whose only covering rows are conditioned is therefore an unmatched period (nominal-rate
     * fallback); conditioned rates need the {@code RULE_DRIVEN} generator, which resolves through
     * the context-accepting resolve, until a later ruling extends condition evaluation to the
     * built-ins.
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

    /**
     * Name recorded in the schedule header when {@link #prepare} did not set
     * {@link AmortizationState#solver}. Generators whose strategy is fixed override it; generators
     * that pick a strategy per run record it in the state.
     */
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
        requireSupportedTailPolicy(element);

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
        BigDecimal balance = money(element.measuredPrincipal(), rounding);
        BigDecimal cumulativePrincipal = money(MoneyUtils.ZERO, rounding);
        BigDecimal cumulativeInterest = money(MoneyUtils.ZERO, rounding);
        int totalPeriods = periods.size();

        // 首期 stub（129号①）：首期窗口整体按「整月 + 零头」计息，与后面的整期窗口并存
        // 不算混用——①只作用于这一笔的首期，其余各期是它们自己的整期（①②在整期上等价）。
        // 求解侧（EqualInstallmentGenerator）吃同一个拆分值，两边不各算各的。
        BigDecimal stubYearFraction = element.firstPeriodStub() && totalPeriods > 0
                ? FirstPeriodStubSplit.yearFraction(periods.get(0).startDate(),
                        periods.get(0).endDate(), element.frequency())
                : null;
        for (int index = 0; index < totalPeriods; index++) {
            PeriodDate period = periods.get(index);
            int periodNo = index + 1;
            int days = dayCount.days(period.startDate(), period.endDate());
            BigDecimal openingBalance = balance;
            RuleOutcome outcome = resolveRule(context.ruleSet(), periodNo, openingBalance, days,
                    element.nominalRate());
            BigDecimal rate = annualRate(element, outcome);
            BigDecimal periodicRate = index == 0 && stubYearFraction != null
                    ? rate.multiply(stubYearFraction)
                    : periodRate(element, period, rate, dayCount);
            BigDecimal accruedInterest = money(openingBalance.multiply(periodicRate), rounding);
            boolean lastPeriod = index == totalPeriods - 1;

            AllocationInput input = new AllocationInput(periodNo, totalPeriods, openingBalance,
                    periodicRate, accruedInterest,
                    element.measuredPrincipal().subtract(cumulativePrincipal), lastPeriod, element.timing(),
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

            String memo = allocation.memo();
            if (index == 0 && stubYearFraction != null) {
                // 「随结果披露」同类口径：首期拆分要显式可见，不得只改数字不留痕。
                memo = (memo == null ? "" : memo + ";") + "首期按整月+零头计息（129号①）";
            }
            installments.add(new Installment(periodNo, period.startDate(), period.endDate(), days,
                    openingBalance, principalDue, accruedInterest, interestPaid, fee, tax, rentDue,
                    closingBalance, cumulativePrincipal, cumulativeInterest, rate,
                    memo));
            balance = closingBalance;
        }

        BigDecimal roundingDifference = checkConservation(element, installments, balance,
                rounding, issues);

        // prepare() may pick a strategy per run and record it in the state; a generator with a fixed
        // strategy keeps the solverName() hook.
        String solver = state.solver() != null ? state.solver() : solverName();
        ScheduleHeader header = new ScheduleHeader(FinancialMetrics.empty(), installments.size(),
                periods.get(0).startDate(), periods.get(periods.size() - 1).endDate(),
                solver, roundingDifference);
        return new Schedule(header, installments, Collections.emptyList(), issues);
    }

    /**
     * The tail-absorption posture of v1: the rounding drift always lands on the last row (the only
     * row that can close the identity under per-row HALF_UP rounding), disclosed signed on the
     * header ({@code roundingDifference}). {@code FIRST_PERIOD} / {@code PROPORTIONAL} need a
     * backward solve — the forward drift only surfaces at the end — so a caller that asks for them
     * is refused loudly instead of silently getting the last-period posture (form-field-gaps
     * ticket 05; this also retires the dead {@code tailAdjustPolicy} input).
     */
    private static void requireSupportedTailPolicy(LeaseElement element) {
        TailAdjustPolicy policy = element.rounding().tailAdjustPolicy();
        if (policy != TailAdjustPolicy.LAST_PERIOD) {
            throw new BusinessException(DomainErrorCode.TAIL_ADJUST_POLICY_UNSUPPORTED,
                    policy.name());
        }
    }

    /**
     * Verifies that principal is conserved and that the schedule ends where the residual policy
     * says it should. Returns the absorbed rounding difference, signed (不得取绝对值后丢弃
     * 符号): closing balance minus expected closing, i.e. positive means the tail adjustment
     * added to the final rent (the schedule left more balance than the residual policy asks for
     * and the final rent absorbs it by charging more), negative means it reduced it.
     */
    private BigDecimal checkConservation(LeaseElement element, List<Installment> installments,
            BigDecimal closingBalance, RoundingPolicy rounding, List<ValidationIssue> issues) {
        BigDecimal principalSum = MoneyUtils.ZERO;
        for (Installment installment : installments) {
            principalSum = principalSum.add(installment.principalDue());
        }
        BigDecimal contractPrincipal = money(element.measuredPrincipal(), rounding);
        BigDecimal residual = element.outstandingResidual();
        BigDecimal expectedPrincipal = money(contractPrincipal.subtract(residual), rounding);
        if (principalSum.subtract(expectedPrincipal).abs().compareTo(TOLERANCE) >= 0) {
            issues.add(new ValidationIssue(IssueLevel.ERROR,
                    CalcErrorCode.PRINCIPAL_CONSERVATION_BROKEN.code(),
                    CalcErrorCode.PRINCIPAL_CONSERVATION_BROKEN.code() + ": 本金合计 "
                            + principalSum.toPlainString()
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
            issues.add(new ValidationIssue(IssueLevel.ERROR,
                    CalcErrorCode.TAIL_BALANCE_MISMATCH.code(),
                    CalcErrorCode.TAIL_BALANCE_MISMATCH.code() + ": 末期余额 "
                            + closingBalance.toPlainString() + " 与预期 "
                            + expectedClosing.toPlainString() + " 不一致", lastPeriodNo));
            return money(MoneyUtils.ZERO, rounding);
        }
        return money(difference, rounding);
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
        private BigDecimal segmentRate;
        private BigDecimal segmentRent;
        private List<PeriodDate> periods;
        private DayCountConvention dayCount;
        private String solver;
        private NumericConfig numericConfig;

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

        /**
         * Annual rate of the rent segment currently being walked (R71 segmented solve);
         * {@code null} until the generator opens the first segment. A generator detects a
         * segment boundary by comparing the period's resolved annual rate against this.
         */
        public BigDecimal segmentRate() {
            return segmentRate;
        }

        public void segmentRate(BigDecimal segmentRate) {
            this.segmentRate = segmentRate;
        }

        /**
         * 数值求解配置（逼近次数/容差/求根区间）：prepare 从计算上下文存入，allocate 读取。
         * 非测算要素，不改任何口径；null 时求解器自行取默认。
         */
        public NumericConfig numericConfig() {
            return numericConfig;
        }

        public void numericConfig(NumericConfig numericConfig) {
            this.numericConfig = numericConfig;
        }

        /**
         * Rent of the segment currently being walked (R71 segmented solve); {@code null} until
         * the first segment's payment is solved.
         */
        public BigDecimal segmentRent() {
            return segmentRent;
        }

        public void segmentRent(BigDecimal segmentRent) {
            this.segmentRent = segmentRent;
        }

        /**
         * The schedule's period windows, recorded by {@link #prepare} when the walk needs them
         * after the fact (the R71 segmented solve re-prices from the remaining windows when the
         * day-count convention does not give every window the same periodic rate); {@code null}
         * when the generator does not need them.
         */
        public List<PeriodDate> periods() {
            return periods;
        }

        public void periods(List<PeriodDate> periods) {
            this.periods = periods;
        }

        /**
         * The day-count convention, recorded by {@link #prepare} alongside {@link #periods()} for
         * generators that re-price during the walk; {@code null} when not needed.
         */
        public DayCountConvention dayCount() {
            return dayCount;
        }

        public void dayCount(DayCountConvention dayCount) {
            this.dayCount = dayCount;
        }

        /**
         * Solver name to record in the schedule header, chosen by {@link #prepare} when the strategy
         * depends on the run (e.g. a closed form or an iterative solve); {@code null} leaves the
         * generator's fixed {@code solverName()} in charge.
         */
        public String solver() {
            return solver;
        }

        public void solver(String solver) {
            this.solver = solver;
        }
    }
}
