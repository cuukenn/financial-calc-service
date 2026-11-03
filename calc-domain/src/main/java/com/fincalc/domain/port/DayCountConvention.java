package com.fincalc.domain.port;

import com.fincalc.common.spi.OrderedExtension;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Day-count convention: how a date range becomes an interest accrual period.
 *
 * <p>Conventions are pluggable through {@code DayCountConventions.all()}, which returns a registry
 * that callers may extend with {@code register(...)} for a single calculation. Adding a convention
 * to every calculation means contributing a {@code DayCountConvention} implementation and
 * registering it where the engine is assembled.
 *
 * <p>All implementations reject a range whose end precedes its start; a zero-length range is valid
 * and yields zero days and a zero year fraction.
 */
public interface DayCountConvention extends OrderedExtension {

    /**
     * Number of accrual days between two dates.
     *
     * @param start accrual start, inclusive
     * @param end   accrual end, exclusive; must not precede {@code start}
     * @return accrual days as defined by this convention
     * @throws IllegalArgumentException when {@code end} precedes {@code start}
     */
    int days(LocalDate start, LocalDate end);

    /**
     * Year fraction between two dates, at scale 12.
     *
     * @param start period start, inclusive
     * @param end   period end, exclusive; must not precede {@code start}
     * @return the convention's year fraction, rounded half-up to scale 12
     * @throws IllegalArgumentException when {@code end} precedes {@code start}
     */
    BigDecimal yearFraction(LocalDate start, LocalDate end);
}
