package io.github.cuukenn.fincalc.domain.support.dateroll;

import io.github.cuukenn.fincalc.domain.port.DateRollConvention;
import java.time.LocalDate;

/** Leaves the nominal date untouched, even on a weekend. */
public final class NoRoll implements DateRollConvention {

    @Override
    public String code() {
        return "NONE";
    }

    @Override
    public LocalDate roll(LocalDate date) {
        return date;
    }
}
