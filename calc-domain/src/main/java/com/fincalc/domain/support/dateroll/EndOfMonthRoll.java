package com.fincalc.domain.support.dateroll;

import com.fincalc.common.date.DateUtils;
import com.fincalc.domain.port.DateRollConvention;
import java.time.LocalDate;

/**
 * Snaps to the last calendar day of the date's month.
 *
 * <p>This convention only aligns the day-of-month; it does not avoid weekends. Compose it with a
 * business-day rule at the call site when a weekday is required.
 */
public final class EndOfMonthRoll implements DateRollConvention {

    @Override
    public String code() {
        return "END_OF_MONTH";
    }

    @Override
    public LocalDate roll(LocalDate date) {
        return DateUtils.endOfMonth(date);
    }
}
