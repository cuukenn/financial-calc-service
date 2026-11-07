package io.github.cuukenn.fincalc.domain.support.dateroll;

import io.github.cuukenn.fincalc.domain.port.DateRollConvention;
import java.time.LocalDate;

/** Rolls forward, but back to the previous weekday when forward would cross the month. */
public final class ModifiedFollowing implements DateRollConvention {

    @Override
    public String code() {
        return "MODIFIED_FOLLOWING";
    }

    @Override
    public LocalDate roll(LocalDate date) {
        LocalDate forward = new Following().roll(date);
        if (forward.getMonth() != date.getMonth()) {
            return new Preceding().roll(date);
        }
        return forward;
    }
}
