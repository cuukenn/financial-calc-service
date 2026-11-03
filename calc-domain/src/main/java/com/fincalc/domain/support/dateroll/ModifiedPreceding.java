package com.fincalc.domain.support.dateroll;

import com.fincalc.domain.port.DateRollConvention;
import java.time.LocalDate;

/** Rolls backward, but forward to the next weekday when backward would leave the month. */
public final class ModifiedPreceding implements DateRollConvention {

    @Override
    public String code() {
        return "MODIFIED_PRECEDING";
    }

    @Override
    public LocalDate roll(LocalDate date) {
        LocalDate backward = new Preceding().roll(date);
        if (backward.getMonth() != date.getMonth()) {
            return new Following().roll(date);
        }
        return backward;
    }
}
