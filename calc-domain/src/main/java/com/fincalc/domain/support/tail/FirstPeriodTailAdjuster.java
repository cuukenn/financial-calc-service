package com.fincalc.domain.support.tail;

import java.math.BigDecimal;

import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.port.TailAdjuster;

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
