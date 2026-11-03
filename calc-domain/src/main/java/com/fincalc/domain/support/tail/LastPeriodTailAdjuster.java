package com.fincalc.domain.support.tail;

import java.math.BigDecimal;

import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.port.TailAdjuster;

/**
 * Absorbs the whole difference into the last installment.
 */
public final class LastPeriodTailAdjuster implements TailAdjuster {

    public static final LastPeriodTailAdjuster INSTANCE = new LastPeriodTailAdjuster();

    private LastPeriodTailAdjuster() {
    }

    @Override
    public String code() {
        return "LAST_PERIOD";
    }

    @Override
    public Installment adjust(Installment target, BigDecimal difference, RoundingPolicy policy) {
        return TailAdjustment.shift(target, difference, policy);
    }
}
