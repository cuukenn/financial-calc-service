package io.github.cuukenn.fincalc.domain.support.tail;

import java.math.BigDecimal;

import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.port.TailAdjuster;

/**
 * Absorbs the whole difference into the first installment.
 */
public final class FirstPeriodTailAdjuster implements TailAdjuster {

    public static final FirstPeriodTailAdjuster INSTANCE = new FirstPeriodTailAdjuster();

    private FirstPeriodTailAdjuster() {
    }

    @Override
    public String code() {
        return "FIRST_PERIOD";
    }

    @Override
    public Installment adjust(Installment target, BigDecimal difference, RoundingPolicy policy) {
        return TailAdjustment.shift(target, difference, policy);
    }
}
