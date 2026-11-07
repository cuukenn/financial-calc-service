package io.github.cuukenn.fincalc.domain.support.tail;

import java.math.BigDecimal;

import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.port.TailAdjuster;

/**
 * Absorbs the difference proportionally across the schedule.
 *
 * <p>A single installment owns 100% of the weight, so {@link #adjust} applies the whole difference
 * to its target. Spreading across rows needs the whole list and is exposed as
 * {@link io.github.cuukenn.fincalc.domain.support.TailAdjusters#applyProportional}.
 */
public final class ProportionalTailAdjuster implements TailAdjuster {

    public static final ProportionalTailAdjuster INSTANCE = new ProportionalTailAdjuster();

    private ProportionalTailAdjuster() {
    }

    @Override
    public String code() {
        return "PROPORTIONAL";
    }

    @Override
    public Installment adjust(Installment target, BigDecimal difference, RoundingPolicy policy) {
        return TailAdjustment.shift(target, difference, policy);
    }
}
