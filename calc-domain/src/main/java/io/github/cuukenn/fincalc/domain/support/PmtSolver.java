package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(windows, "windows");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(annualRate, "annualRate");
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("windows must not be empty");
        }
        BigDecimal residual = MoneyUtils.zeroIfNull(residualValue);
        if (annualRate.signum() == 0) {
            return MoneyUtils.out(principal.subtract(residual)
                    .divide(BigDecimal.valueOf(windows.size()), MC));
        }

        BigDecimal low = BigDecimal.ZERO;
        if (error(principal, windows, dayCount, annualRate, residual, low).signum() <= 0) {
            // The residual alone already covers the grown principal: nothing has to be repaid.
            return MoneyUtils.out(BigDecimal.ZERO);
        }
        BigDecimal high = principal.divide(BigDecimal.valueOf(windows.size()), MC);
        for (int attempt = 0; attempt < MAX_BRACKET_DOUBLINGS; attempt++) {
            if (error(principal, windows, dayCount, annualRate, residual, high).signum() < 0) {
                return bisect(principal, windows, dayCount, annualRate, residual, low, high);
            }
            high = high.multiply(TWO, MC);
        }
        throw new IllegalStateException("no rent bracket found within " + MAX_BRACKET_DOUBLINGS
                + " doublings for " + windows.size() + " periods");
    }

    /**
     * Balance left after the windows amortize {@code principal} at a level {@code rent}: interest is
     * accrued on the opening balance of every window and the rent is subtracted from it. Unrounded —
     * this is the projection the bisection converges on, and it is public so a caller can check a
     * rent against it.
     */
    public static BigDecimal closingBalance(BigDecimal principal, List<PeriodDate> windows,
            DayCountConvention dayCount, BigDecimal annualRate, BigDecimal rent) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(windows, "windows");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(annualRate, "annualRate");
        Objects.requireNonNull(rent, "rent");
        BigDecimal balance = principal;
        for (PeriodDate window : windows) {
            BigDecimal rate = annualRate.multiply(dayCount.yearFraction(window.startDate(),
                    window.endDate()));
            balance = balance.add(balance.multiply(rate, MC), MC).subtract(rent, MC);
        }
        return balance;
    }

    private static BigDecimal bisect(BigDecimal principal, List<PeriodDate> windows,
            DayCountConvention dayCount, BigDecimal annualRate, BigDecimal residual,
            BigDecimal low, BigDecimal high) {
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            BigDecimal middle = low.add(high).divide(TWO, MC);
            BigDecimal error = error(principal, windows, dayCount, annualRate, residual, middle);
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
    private static BigDecimal error(BigDecimal principal, List<PeriodDate> windows,
            DayCountConvention dayCount, BigDecimal annualRate, BigDecimal residual, BigDecimal rent) {
        return closingBalance(principal, windows, dayCount, annualRate, rent).subtract(residual);
    }
}
