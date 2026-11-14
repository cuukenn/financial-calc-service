package io.github.cuukenn.fincalc.domain.support.algo;

import java.math.BigDecimal;
import java.util.List;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.DomainErrorCode;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.RuleOutcome;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import io.github.cuukenn.fincalc.domain.support.AbstractAmortizationGenerator;
import io.github.cuukenn.fincalc.domain.support.NumericConfig;
import io.github.cuukenn.fincalc.domain.support.FirstPeriodStubSplit;
import io.github.cuukenn.fincalc.domain.support.PmtSolver;

/**
 * Equal installment (annuity) schedule: every period carries the same rent, with interest on
 * the opening balance and principal as the remainder.
 *
 * <p>Without a rule set the level rent is solved once in {@link #prepare} by {@link PmtSolver}:
 * closed form when the day-count convention gives every period the same rate, bisection over the
 * schedule's own windows when it does not. Every period then allocates
 * {@code pmt - accruedInterest} to principal; the final period absorbs whatever principal is left
 * so the schedule closes exactly.
 *
 * <p>With a rule set the per-period rates are rule-resolved (R70) and the element's
 * {@code rateChangePolicy} picks the R71 reaction (segment boundaries are the first periods of
 * maximal runs of consecutive periods whose resolved annual rate is equal):
 * <ul>
 *   <li>{@code RESOLVE} (default): each segment re-solves the PMT from the segment-opening
 *       balance over the remaining periods of the whole schedule at the segment rate
 *       ({@code SEGMENTED_RESOLVE}); rents are equal within the segment.</li>
 *   <li>{@code FIXED_RENT}: the PMT is solved once from the initial rate; rate changes alter
 *       only the interest/principal split ({@code FIXED_RENT}).</li>
 *   <li>{@code DRIFT}: every period re-solves from the current balance over the remaining
 *       periods at the current rate ({@code DRIFT}).</li>
 * </ul>
 */
public final class EqualInstallmentGenerator extends AbstractAmortizationGenerator {

    private static final BigDecimal RATE_EPSILON = new BigDecimal("0.000000000001");

    @Override
    public String code() {
        return "EQUAL_INSTALLMENT";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean supports(CalculationContext context) {
        return context.element().method() == RepaymentMethodId.EQUAL_INSTALLMENT
                && context.element().timing() == PaymentTiming.IN_ARREARS;
    }

    @Override
    protected void validate(LeaseElement element) {
        if (element.timing() != PaymentTiming.IN_ARREARS) {
            throw new BusinessException(DomainErrorCode.UNSUPPORTED_PAYMENT_TIMING,
                    element.timing());
        }
    }

    @Override
    protected void prepare(CalculationContext context, List<PeriodDate> periods,
            AmortizationState state) {

        state.numericConfig(context.numericConfig());
        LeaseElement element = context.element();
        DayCountConvention dayCount = context.dayCount();
        BigDecimal principal = money(element.measuredPrincipal(), element);
        BigDecimal residual = element.outstandingResidual();

        if (context.ruleSet() == null) {
            // R71: no rule set means a single segment at the nominal rate — the pre-segmented
            // behavior, unchanged.
            BigDecimal stubOverride = firstStubOverride(element, periods);
            // The closed form stays exclusive to the stub-free path: a stub first window prices
            // 129号①, whose rate generally leaves the uniform-rate domain the annuity formula
            // assumes (and on THIRTY_360 month-end edges it differs from the 30/360 fraction
            // too), so the bisection over the mixed rate list prices it.
            if (stubOverride == null
                    && PmtSolver.isConstantPeriodRate(dayCount)
                    && closedFormPricing(context, periods)) {
                BigDecimal rate = periodRate(element, periods.get(0), element.nominalRate(),
                        dayCount);
                state.pmt(PmtSolver.closedForm(principal, rate, periods.size(), residual));
                state.solver("CLOSED_FORM");
            } else {
                state.pmt(PmtSolver.iterative(principal, periods, dayCount, element.nominalRate(),
                        residual, stubOverride, context.numericConfig()));
                state.solver("ITERATIVE");
            }
            return;
        }

        // R71: with a rule set the resolved rates may vary per period; the rate-change policy
        // decides how the rent reacts. RESOLVE and DRIFT re-solve lazily in allocate from the
        // actual opening balances, so no rent is prepared here.
        state.periods(periods);
        state.dayCount(dayCount);
        switch (element.rateChangePolicy()) {
            case FIXED_RENT:
                BigDecimal initialAnnual = initialAnnualRate(context, periods);
                state.pmt(solveSegment(principal, initialAnnual,
                        periodRate(element, periods.get(0), initialAnnual, dayCount),
                        periods.size(), periods, dayCount, residual,
                        firstStubOverride(element, periods), context.numericConfig()));
                state.solver("FIXED_RENT");
                break;
            case DRIFT:
                state.solver("DRIFT");
                break;
            case RESOLVE:
            default:
                state.solver("SEGMENTED_RESOLVE");
                break;
        }
    }

    /**
     * Rent that amortizes {@code opening} to {@code residual} over {@code remaining} periods at
     * one rate. When the remaining windows actually all carry the same periodic rate the closed
     * form is R71's period-count definition verbatim; a non-uniform run of windows (an
     * {@code ACT_*} convention, or a stub window under a nominally constant convention) solves
     * by bisection over those windows instead, because pricing the whole remaining term at the
     * opening window's rate would misstate the rent.
     */
    private static BigDecimal solveSegment(BigDecimal opening, BigDecimal annual,
            BigDecimal periodicRate, int remaining, List<PeriodDate> remainingWindows,
            DayCountConvention dayCount, BigDecimal residual, BigDecimal stubOverride,
            NumericConfig config) {
        // Each window's rate is derived once per segment solve and then consumed twice — by the
        // uniformity test and by the bisection's ~50 evaluations . The products are
        // the same unrounded {@code annual × yearFraction(window)} in window order, so the rent the
        // segment prices is bit-identical to the separately-derived version.
        List<BigDecimal> periodRates = PmtSolver.periodRates(annual, remainingWindows, dayCount,
                stubOverride);
        if (uniform(periodRates)) {
            return PmtSolver.closedForm(opening, periodicRate, remaining, residual);
        }
        return PmtSolver.iterative(opening, periodRates, annual, residual, config);
    }

    /** Whether every window of a segment accrues the same periodic rate. */
    private static boolean uniform(List<BigDecimal> periodRates) {
        BigDecimal firstRate = periodRates.get(0);
        for (BigDecimal rate : periodRates) {
            if (rate.subtract(firstRate).abs().compareTo(RATE_EPSILON) > 0) {
                return false;
            }
        }
        return true;
    }

    /** Annual rate of the opening period: the rule-resolved rate, else the nominal rate. */
    private BigDecimal initialAnnualRate(CalculationContext context, List<PeriodDate> periods) {
        PeriodDate first = periods.get(0);
        RuleOutcome outcome = resolveRule(context.ruleSet(), 1, money(context.element().measuredPrincipal(),
                context.element()), context.dayCount().days(first.startDate(), first.endDate()),
                context.element().nominalRate());
        return annualRate(context.element(), outcome);
    }

    /**
     * Whether the level rent may be priced by the closed form. The annuity formula assumes one
     * periodic rate, so the nominal-derived periodic rates must be uniform across the schedule.
     * Only ever called from the no-rule-set path of {@link #prepare}: without a rule set a stub
     * period that breaks uniformity fails fast ({@code FCS-DOM-2002}) — better than emitting a
     * schedule with negative balances that still passes the conservation check. Runs with a rule
     * set never reach this guard; the R71 segmented solve ({@code rateChangePolicy}) prices
     * those instead.
     */
    private static boolean closedFormPricing(CalculationContext context, List<PeriodDate> periods) {
        // Deliberately inlines the periodRate hook's formula (nominal x yearFraction) rather than
        // calling the hook: a subclass that overrides the hook changes the walk's accrual, and
        // this guard must stay in sync with that pricing or the two can desync.
        BigDecimal firstRate = context.element().nominalRate()
                .multiply(context.dayCount().yearFraction(periods.get(0).startDate(),
                        periods.get(0).endDate()));
        for (int index = 1; index < periods.size(); index++) {
            PeriodDate period = periods.get(index);
            BigDecimal rate = context.element().nominalRate()
                    .multiply(context.dayCount().yearFraction(period.startDate(), period.endDate()));
            if (rate.subtract(firstRate).abs().compareTo(RATE_EPSILON) > 0) {
                throw new BusinessException(DomainErrorCode.NON_UNIFORM_PERIOD_RATE,
                        index + 1, rate.toPlainString(), firstRate.toPlainString());
            }
        }
        return true;
    }

    @Override
    protected Allocation allocate(AllocationInput input, AmortizationState state) {
        BigDecimal principal;
        if (input.lastPeriod()) {
            principal = input.remainingPrincipal().subtract(input.element().outstandingResidual());
        } else {
            principal = rent(input, state).subtract(input.accruedInterest());
        }
        BigDecimal rounded = money(principal, input.element());
        if (rounded.signum() < 0) {
            throw new BusinessException(DomainErrorCode.NEGATIVE_PRINCIPAL_DUE,
                    input.periodNo(), rounded.toPlainString());
        }
        return new Allocation(principal, input.accruedInterest());
    }

    /**
     * Rent of one period. When {@link #prepare} solved a single level rent (the whole no-rule-set
     * path and {@code FIXED_RENT}) it is used unchanged. Otherwise the R71 policy decides:
     * {@code DRIFT} re-solves every period from the current balance over the remaining periods at
     * the current rate; {@code RESOLVE} re-solves only when the resolved annual rate changes —
     * a segment boundary — from the segment-opening balance over the remaining periods of the
     * whole schedule at the segment rate.
     */
    private BigDecimal rent(AllocationInput input, AmortizationState state) {
        BigDecimal prepared = state.pmt();
        if (prepared != null) {
            return prepared;
        }
        int remaining = input.totalPeriods() - input.periodNo() + 1;
        List<PeriodDate> remainingWindows = state.periods().subList(input.periodNo() - 1,
                input.totalPeriods());
        DayCountConvention dayCount = state.dayCount();
        BigDecimal residual = input.element().outstandingResidual();
        BigDecimal annual = annualRate(input.element(), input.ruleOutcome());
        if (input.element().rateChangePolicy() == RateChangePolicy.DRIFT) {
            return solveSegment(input.openingBalance(), annual, input.periodRate(), remaining,
                    remainingWindows, dayCount, residual, firstStubOverride(input.element(),
                            remainingWindows, input.periodNo()), state.numericConfig());
        }
        BigDecimal segmentRate = state.segmentRate();
        if (segmentRate == null || annual.compareTo(segmentRate) != 0) {
            state.segmentRate(annual);
            state.segmentRent(solveSegment(input.openingBalance(), annual, input.periodRate(),
                    remaining, remainingWindows, dayCount, residual, firstStubOverride(
                            input.element(), remainingWindows, input.periodNo()),
                    state.numericConfig()));
        }
        return state.segmentRent();
    }

    /**
     * The stub override for a solve over {@code windows}: the year fraction of 129号① when the
     * element carries the stub and {@code windows} starts at the schedule's first window — the
     * solve and the amortization row must price the same split, or the schedule would not close.
     */
    private static BigDecimal firstStubOverride(LeaseElement element, List<PeriodDate> windows) {
        return windows.isEmpty() || !element.firstPeriodStub()
                ? null
                : FirstPeriodStubSplit.yearFraction(windows.get(0).startDate(),
                        windows.get(0).endDate(), element.frequency());
    }

    /** {@code periodNo <= 0} marks the whole-schedule solves of {@link #prepare}. */
    private static BigDecimal firstStubOverride(LeaseElement element, List<PeriodDate> windows,
            int periodNo) {
        return periodNo == 1 ? firstStubOverride(element, windows) : null;
    }

    private static BigDecimal money(BigDecimal value, LeaseElement element) {
        return value.setScale(element.rounding().scale(), element.rounding().mode());
    }
}
