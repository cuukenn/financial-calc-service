package com.fincalc.domain.support.dateroll;

import com.fincalc.common.date.DateUtils;
import com.fincalc.domain.port.DateRollConvention;
import java.time.LocalDate;

/**
 * Snaps to the last day of the date's month.
 *
 * <p>When that last day is a weekend, the roll moves backward to the last weekday of the same month:
 * a payment date never leaves the month it belongs to, and never lands on a weekend.
 */
public final class EndOfMonthRoll implements DateRollConvention {

    @Override
    public String code() {
        return "END_OF_MONTH";
    }

    @Override
    public LocalDate roll(LocalDate date) {
        LocalDate rolled = DateUtils.endOfMonth(date);
        while (Following.isWeekend(rolled)) {
            rolled = rolled.minusDays(1);
        }
        return rolled;
    }
}
