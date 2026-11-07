package io.github.cuukenn.fincalc.domain.support.dateroll;

import io.github.cuukenn.fincalc.domain.port.DateRollConvention;
import java.time.DayOfWeek;
import java.time.LocalDate;

/** Moves a weekend date forward to the next weekday. */
public final class Following implements DateRollConvention {

    @Override
    public String code() {
        return "FOLLOWING";
    }

    @Override
    public LocalDate roll(LocalDate date) {
        LocalDate rolled = date;
        while (isWeekend(rolled)) {
            rolled = rolled.plusDays(1);
        }
        return rolled;
    }

    static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
