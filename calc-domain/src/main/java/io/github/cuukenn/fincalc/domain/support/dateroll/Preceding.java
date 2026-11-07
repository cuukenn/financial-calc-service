package io.github.cuukenn.fincalc.domain.support.dateroll;

import io.github.cuukenn.fincalc.domain.port.DateRollConvention;
import java.time.LocalDate;

/** Moves a weekend date backward to the previous weekday. */
public final class Preceding implements DateRollConvention {

    @Override
    public String code() {
        return "PRECEDING";
    }

    @Override
    public LocalDate roll(LocalDate date) {
        LocalDate rolled = date;
        while (Following.isWeekend(rolled)) {
            rolled = rolled.minusDays(1);
        }
        return rolled;
    }
}
