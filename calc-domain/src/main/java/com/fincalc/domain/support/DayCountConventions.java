package com.fincalc.domain.support;

import com.fincalc.common.spi.ExtensionRegistry;
import com.fincalc.domain.port.DayCountConvention;
import com.fincalc.domain.support.daycount.Act360;
import com.fincalc.domain.support.daycount.Act36525;
import com.fincalc.domain.support.daycount.Act365F;
import com.fincalc.domain.support.daycount.ActAct;
import com.fincalc.domain.support.daycount.Thirty360;
import com.fincalc.domain.support.daycount.ThirtyE360;

/** Built-in day-count conventions, addressable by code. */
public final class DayCountConventions {

    private DayCountConventions() {
    }

    /**
     * Returns a fresh registry holding the six built-in conventions.
     *
     * <p>The registry is mutable: call {@code register} to override a built-in for one calculation.
     *
     * @return registry keyed by {@link DayCountConvention#code()}
     */
    public static ExtensionRegistry<DayCountConvention> all() {
        return ExtensionRegistry.<DayCountConvention>empty()
                .register(new Act360())
                .register(new Act365F())
                .register(new ActAct())
                .register(new Thirty360())
                .register(new ThirtyE360())
                .register(new Act36525());
    }
}
