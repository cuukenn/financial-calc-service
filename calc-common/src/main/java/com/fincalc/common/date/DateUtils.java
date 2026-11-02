package com.fincalc.common.date;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Date helpers shared by schedule and accrual calculations.
 *
 * <p>All dates are represented as {@link LocalDate}; the legacy {@code Date} / {@code Calendar}
 * APIs are never used.
 */
public final class DateUtils {

    private DateUtils() {
    }

    /**
     * Returns the last day of the month of {@code date}.
     *
     * @param date the reference date
     * @return the last day of {@code date}'s month
     */
    public static LocalDate endOfMonth(LocalDate date) {
        return date.withDayOfMonth(date.lengthOfMonth());
    }

    /**
     * Tells whether {@code date} is the last day of its month.
     *
     * @param date the date to test
     * @return {@code true} when {@code date} is the last day of its month
     */
    public static boolean isEndOfMonth(LocalDate date) {
        return date.getDayOfMonth() == date.lengthOfMonth();
    }

    /**
     * Counts the days in the half-open range {@code [startInclusive, endExclusive)}.
     *
     * @param startInclusive the first day, inclusive
     * @param endExclusive the last day, exclusive
     * @return the number of days between the two dates
     * @throws ArithmeticException if the result does not fit in an {@code int}
     */
    public static int daysBetween(LocalDate startInclusive, LocalDate endExclusive) {
        return Math.toIntExact(ChronoUnit.DAYS.between(startInclusive, endExclusive));
    }

    /**
     * Adds {@code months} to {@code date}, keeping an end-of-month anchor on the last day of the
     * target month. Non end-of-month dates are shifted by {@link LocalDate#plusMonths(long)}, which
     * clamps to the target month's last day when the day-of-month does not exist.
     *
     * @param date the anchor date
     * @param months the number of months to add, may be negative
     * @return the shifted date
     */
    public static LocalDate plusMonthsKeepEom(LocalDate date, int months) {
        LocalDate shifted = date.plusMonths(months);
        return isEndOfMonth(date) ? endOfMonth(shifted) : shifted;
    }
}
