package com.fincalc.domain.port;

import java.math.BigDecimal;

import com.fincalc.common.spi.OrderedExtension;
import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.RoundingPolicy;

/**
 * Absorbs a rounding difference into an existing installment.
 *
 * <p>The caller computes {@code difference = expectedTotal - currentTotal} and passes it in;
 * implementations never recompute totals themselves, so the same difference cannot be applied
 * twice by accident.
 */
public interface TailAdjuster extends OrderedExtension {

    /**
     * Returns a copy of {@code target} whose rent and principal are shifted by {@code difference}.
     *
     * @param target      installment to adjust; never {@code null}
     * @param difference  expected total minus current total, in currency units
     * @param policy      output scale and rounding mode
     * @return the adjusted installment; the input is left untouched
     */
    Installment adjust(Installment target, BigDecimal difference, RoundingPolicy policy);
}
