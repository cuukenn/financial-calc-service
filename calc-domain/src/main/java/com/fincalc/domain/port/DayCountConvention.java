package com.fincalc.domain.port;

import com.fincalc.common.spi.OrderedExtension;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Day-count convention: how a date range becomes an interest accrual period.
 *
 * <p>Conventions are pluggable through {@code DayCountConventions.all()}, so a new market
 * convention can be added without touching the calculation engine.
 */
public interface DayCountConvention extends OrderedExtension {

    /**
     * Number of accrual days between two dates.
     *
     * @param start accrual start, inclusive
     * @param end   accrual end, exclusive
     * @return accrual days as defined by this convention
     */
    int days(LocalDate start, LocalDate end);

    /**
     * Year fraction between two dates, at scale 12.
     *
     * @param start period start, inclusive
     * @param end   period end, exclusive
     * @return {@code days / basis}, rounded half-up to scale 12
     */
    BigDecimal yearFraction(LocalDate start, LocalDate end);
}
