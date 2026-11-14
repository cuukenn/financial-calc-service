package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Solves the level rent of an annuity schedule, in the two ways the calculation can be exact.
 *
 * <p>Closed form (design document §7.5): when every period carries the same periodic rate, the
 * annuity-immediate formula prices the rent in one step. When the periods do not carry the same
 * rate — an {@code ACT_*} convention, or a stub period — the annuity formula is no longer exact and
 * the rent is found by bisection over the schedule's own balance walk.
 *
 * <p>Both paths price the <em>ideal</em> rent and return it at the shared output scale: the
 * projection is unrounded, the answer is rounded to cents, and a generator then rounds every amount
 * at the element's rounding policy and absorbs the remainder in the final period. The internal solve
 * converges to {@link #TOLERANCE}, so what comes back is the exact rent to the nearest cent.
 *
 * <p>Rents are assumed to fall due at the end of their period. A schedule paid in advance needs its
 * own balance walk and is not priced here.
 *
 * <p><b>The walk's rates are derived once per solve</b> ({@link #periodRates}): a bisection evaluates
 * the projection ~50 times over the same windows, and the rate each window accrues is a product of
 * the window and the nominal rate alone, never of the candidate rent. Deriving it per evaluation
 * retook the convention's {@code days ÷ year} quotient and its exact product tens of millions of
 * times on the 1200-period / 400-segment cell — the single largest cost the engine pays
 * (AGENTS.md §6 N-05). The refactor changes where the product is computed, not what it is: same
 * unrounded multiply, same window order, same {@link MathContext#DECIMAL128} steps, so every schedule
 * amount is bit-identical (AGENTS.md §6 N-03/N-04). The iteration budget and {@link #TOLERANCE} are
 * untouched.
 */
public final class PmtSolver {

    /** How close to the residual balance a bisected rent must bring the projection. */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.0000000001");

    /** Bisection budget; the projection converges well inside it for lease-length schedules. */
    public static final int MAX_ITERATIONS = 100;

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWO = new BigDecimal("2");

    /** Conventions whose periods all accrue the same number of days, so one rate prices them all. */
    private static final Set<String> CONSTANT_RATE_CODES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("THIRTY_360", "THIRTY_E_360")));

    private static final int MAX_BRACKET_DOUBLINGS = 64;

    /**
     * Diagnostic counters for AGENTS.md §6 N-01 attribution — how many iterative rent solves a run
     * paid for, how many candidate rents it evaluated, and how many balance-walk steps those
     * evaluations took. They carry no calculation meaning (nothing reads them to produce a number)
     * and exist so a cost regression in the segmented cells can be located instead of guessed at.
     */
    public static final AtomicLong SOLVE_COUNT = new AtomicLong();
    public static final AtomicLong EVALUATION_COUNT = new AtomicLong();
    public static final AtomicLong WALK_STEP_COUNT = new AtomicLong();

    private PmtSolver() {
    }

    /**
     * Whether a convention gives every period the same periodic rate, which is what the closed form
     * requires. Conventions outside this set — including any registered by an extension — are priced
     * period by period, which is correct whatever their windows look like.
     */
    public static boolean isConstantPeriodRate(DayCountConvention dayCount) {
        return dayCount != null && CONSTANT_RATE_CODES.contains(dayCount.code());
    }

    /**
     * Level rent of {@code periods} equal periods each carrying {@code periodicRate}, leaving
     * {@code residualValue} outstanding at maturity.
     *
     * @param principal     amount financed at the first accrual date
     * @param periodicRate  rate of one period, as a fraction
     * @param periods       number of equal periods, at least 1
     * @param residualValue balloon left unpaid at maturity, may be null or zero
     * @return the rent at the shared output scale
     * @throws IllegalArgumentException when {@code periods} is below 1
     */
    public static BigDecimal closedForm(BigDecimal principal, BigDecimal periodicRate, int periods,
            BigDecimal residualValue) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(periodicRate, "periodicRate");
        if (periods < 1) {
            throw new IllegalArgumentException("periods must be at least 1: " + periods);
        }
        BigDecimal residual = MoneyUtils.zeroIfNull(residualValue);
        if (periodicRate.signum() == 0) {
            return MoneyUtils.out(principal.subtract(residual)
                    .divide(BigDecimal.valueOf(periods), MC));
        }

        BigDecimal growth = BigDecimal.ONE.add(periodicRate).pow(periods, MC);
        BigDecimal denominator = growth.subtract(BigDecimal.ONE);
        if (denominator.signum() == 0) {
            // A rate this small is invisible at this precision: compounding cannot be resolved.
            return MoneyUtils.out(principal.subtract(residual)
                    .divide(BigDecimal.valueOf(periods), MC));
        }
        BigDecimal presentResidual = residual.divide(growth, MC);
        BigDecimal numerator = principal.subtract(presentResidual)
                .multiply(periodicRate)
                .multiply(growth);
        return MoneyUtils.out(numerator.divide(denominator, MC));
    }

    /**
     * Level rent over the given windows, each accruing {@code annualRate} for its own share of the
     * year, found by bisection until the projected closing balance is within {@link #TOLERANCE} of
     * {@code residualValue}.
     *
     * @param principal     amount financed at the first window's start
     * @param windows       the schedule's own period windows, in order
     * @param dayCount      convention that turns each window into a year fraction
     * @param annualRate    nominal annual rate, as a fraction
     * @param residualValue balloon left outstanding at maturity, may be null or zero
     * @return the rent at the shared output scale
     * @throws IllegalStateException  when no bracket contains the rent within the doubling budget
     * @throws IllegalArgumentException when {@code windows} is empty
     */
    public static BigDecimal iterative(BigDecimal principal, List<PeriodDate> windows,
            DayCountConvention dayCount, BigDecimal annualRate, BigDecimal residualValue) {
        return iterative(principal, windows, dayCount, annualRate, residualValue, null);
    }

    /**
     * {@link #iterative(BigDecimal, List, DayCountConvention, BigDecimal, BigDecimal)} with the
     * first window's stub override（see the {@code periodRates} overload）; {@code null} keeps
     * the plain derivation.
     */
    public static BigDecimal iterative(BigDecimal principal, List<PeriodDate> windows,
            DayCountConvention dayCount, BigDecimal annualRate, BigDecimal residualValue,
            BigDecimal firstWindowYearFraction) {
        Objects.requireNonNull(dayCount, "dayCount");
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("windows must not be empty");
        }
        return iterative(principal, periodRates(annualRate, windows, dayCount,
                firstWindowYearFraction), annualRate, residualValue);
    }

    /**
     * {@link #iterative(BigDecimal, List, DayCountConvention, BigDecimal, BigDecimal)} over window
     * rates the caller already derived — the seam that lets a segmented run derive each window's rate
     * once for the segment instead of once per uniformity test, once more per solve and ~50 more
     * times inside the bisection (AGENTS.md §6 N-05). The values are the same
     * {@code annualRate × yearFraction(window)} products in the same order, so the solved rent is
     * bit-identical (AGENTS.md §6 N-04).
     *
     * <p>{@code annualRate} is consulted only for the zero-rate shortcut a flat nominal rate gets;
     * a window whose own fraction is zero (a same-date window) must not trigger it, which is why the
     * check reads the nominal rate and not the derived list.
     *
     * @param principal     amount financed at the first window's start
     * @param periodRates   each window's rate at the nominal rate, in window order; never empty
     * @param annualRate    the nominal annual rate those rates were derived from
     * @param residualValue balloon left outstanding at maturity, may be null or zero
     * @return the rent at the shared output scale
     */
    public static BigDecimal iterative(BigDecimal principal, List<BigDecimal> periodRates,
            BigDecimal annualRate, BigDecimal residualValue) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(periodRates, "periodRates");
        Objects.requireNonNull(annualRate, "annualRate");
        if (periodRates.isEmpty()) {
            throw new IllegalArgumentException("periodRates must not be empty");
        }
        SOLVE_COUNT.incrementAndGet();
        BigDecimal residual = MoneyUtils.zeroIfNull(residualValue);
        if (annualRate.signum() == 0) {
            return MoneyUtils.out(principal.subtract(residual)
                    .divide(BigDecimal.valueOf(periodRates.size()), MC));
        }

        BigDecimal low = BigDecimal.ZERO;
        if (error(principal, periodRates, residual, low).signum() <= 0) {
            // The residual alone already covers the grown principal: nothing has to be repaid.
            return MoneyUtils.out(BigDecimal.ZERO);
        }
        BigDecimal high = principal.divide(BigDecimal.valueOf(periodRates.size()), MC);
        for (int attempt = 0; attempt < MAX_BRACKET_DOUBLINGS; attempt++) {
            if (error(principal, periodRates, residual, high).signum() < 0) {
                return bisect(principal, periodRates, residual, low, high);
            }
            high = high.multiply(TWO, MC);
        }
        throw new IllegalStateException("no rent bracket found within " + MAX_BRACKET_DOUBLINGS
                + " doublings for " + periodRates.size() + " periods");
    }

    /**
     * Balance left after the windows amortize {@code principal} at a level {@code rent}: interest is
     * accrued on the opening balance of every window and the rent is subtracted from it. Unrounded —
     * this is the projection the bisection converges on, and it is public so a caller can check a
     * rent against it.
     */
    public static BigDecimal closingBalance(BigDecimal principal, List<PeriodDate> windows,
            DayCountConvention dayCount, BigDecimal annualRate, BigDecimal rent) {
        Objects.requireNonNull(rent, "rent");
        return walk(principal, periodRates(annualRate, windows, dayCount), rent);
    }

    /**
     * The periodic rate of every window — {@code annualRate × yearFraction(window)} in window order.
     *
     * <p>The product belongs to the window and the nominal rate, not to the candidate rent, so a
     * bisection that walks the same windows ~50 times needs it once (AGENTS.md §6 N-05). Deriving it
     * inside the walk retook the convention's {@code days ÷ 360} quotient and the exact product on
     * every evaluation: on the 1200-period / 400-segment cell that is tens of millions of quotients
     * for a number the schedule already determined.
     *
     * <p>No rounding is introduced here: the product is the same unrounded {@link BigDecimal#multiply}
     * the inline walk performed, in the same order (AGENTS.md §6 N-03/N-04, §1 P-07).
     */
    public static List<BigDecimal> periodRates(BigDecimal annualRate, List<PeriodDate> windows,
            DayCountConvention dayCount) {
        return periodRates(annualRate, windows, dayCount, null);
    }

    /**
     * Same derivation with an optional override for the <em>first</em> window of {@code windows}:
     * the caller hands in the stub year fraction（129号① 整月+零头，{@code annualRate × 分值}）
     * when that window is the schedule's first and the element carries
     * {@code firstPeriodStub} — the solve and the amortization row must price the same split,
     * or the rent would not close the schedule. {@code null} keeps the plain derivation.
     */
    public static List<BigDecimal> periodRates(BigDecimal annualRate, List<PeriodDate> windows,
            DayCountConvention dayCount, BigDecimal firstWindowYearFraction) {
        Objects.requireNonNull(annualRate, "annualRate");
        Objects.requireNonNull(windows, "windows");
        Objects.requireNonNull(dayCount, "dayCount");
        List<BigDecimal> rates = new ArrayList<>(windows.size());
        boolean first = true;
        for (PeriodDate window : windows) {
            if (first && firstWindowYearFraction != null) {
                rates.add(annualRate.multiply(firstWindowYearFraction));
            } else {
                rates.add(annualRate.multiply(dayCount.yearFraction(window.startDate(),
                        window.endDate())));
            }
            first = false;
        }
        return rates;
    }

    /**
     * The balance walk over precomputed periodic rates — the projection the bisection converges on,
     * one {@code balance(1 + rate) − rent} step per rate, rounded at {@link MathContext#DECIMAL128}
     * exactly as the window-based walk did.
     */
    private static BigDecimal walk(BigDecimal principal, List<BigDecimal> periodRates,
            BigDecimal rent) {
        BigDecimal balance = principal;
        for (BigDecimal rate : periodRates) {
            balance = balance.add(balance.multiply(rate, MC), MC).subtract(rent, MC);
        }
        return balance;
    }

    private static BigDecimal bisect(BigDecimal principal, List<BigDecimal> periodRates,
            BigDecimal residual, BigDecimal low, BigDecimal high) {
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            BigDecimal middle = low.add(high).divide(TWO, MC);
            BigDecimal error = error(principal, periodRates, residual, middle);
            if (error.abs().compareTo(TOLERANCE) <= 0) {
                return MoneyUtils.out(middle);
            }
            if (error.signum() > 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        // Out of iterations: the best rent seen is still within the shrinking interval.
        return MoneyUtils.out(low.add(high).divide(TWO, MC));
    }

    /** Positive when a rent leaves more than the residual outstanding; the walk is monotone in rent. */
    private static BigDecimal error(BigDecimal principal, List<BigDecimal> periodRates,
            BigDecimal residual, BigDecimal rent) {
        EVALUATION_COUNT.incrementAndGet();
        WALK_STEP_COUNT.addAndGet(periodRates.size());
        return walk(principal, periodRates, rent).subtract(residual);
    }
}
