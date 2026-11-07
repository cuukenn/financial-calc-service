package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.spi.ExtensionRegistry;
import io.github.cuukenn.fincalc.domain.port.DateRollConvention;
import io.github.cuukenn.fincalc.domain.support.dateroll.EndOfMonthRoll;
import io.github.cuukenn.fincalc.domain.support.dateroll.Following;
import io.github.cuukenn.fincalc.domain.support.dateroll.ModifiedFollowing;
import io.github.cuukenn.fincalc.domain.support.dateroll.ModifiedPreceding;
import io.github.cuukenn.fincalc.domain.support.dateroll.NoRoll;
import io.github.cuukenn.fincalc.domain.support.dateroll.Preceding;

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
