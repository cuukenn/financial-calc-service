package com.fincalc.domain.support;

import com.fincalc.common.spi.ExtensionRegistry;
import com.fincalc.domain.port.DateRollConvention;
import com.fincalc.domain.support.dateroll.EndOfMonthRoll;
import com.fincalc.domain.support.dateroll.Following;
import com.fincalc.domain.support.dateroll.ModifiedFollowing;
import com.fincalc.domain.support.dateroll.ModifiedPreceding;
import com.fincalc.domain.support.dateroll.NoRoll;
import com.fincalc.domain.support.dateroll.Preceding;

/** Built-in date-roll conventions, addressable by code. */
public final class DateRollConventions {

    private DateRollConventions() {
    }

    /**
     * Returns a fresh registry holding the six built-in conventions.
     *
     * <p>The registry is mutable: call {@code register} to override a built-in for one calculation.
     *
     * @return registry keyed by {@link DateRollConvention#code()}
     */
    public static ExtensionRegistry<DateRollConvention> all() {
        return ExtensionRegistry.<DateRollConvention>empty()
                .register(new NoRoll())
                .register(new Following())
                .register(new ModifiedFollowing())
                .register(new Preceding())
                .register(new ModifiedPreceding())
                .register(new EndOfMonthRoll());
    }
}
