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
     * <p><strong>Derived fields are copied unchanged.</strong> {@code closingBalance},
     * {@code cumulativePrincipal} and {@code cumulativeInterest} still describe the unadjusted row.
     * The caller must recompute them (or let the generator rebuild the schedule) before the result is
     * published; otherwise the schedule violates the balance identity {@code opening - principal = closing}.
     *
     * @param target      installment to adjust; never {@code null}
     * @param difference  expected total minus current total, in currency units
     * @param policy      output scale and rounding mode
     * @return the adjusted installment; the input is left untouched
     * @throws IllegalArgumentException when the shift would drive principal below zero
     */
    Installment adjust(Installment target, BigDecimal difference, RoundingPolicy policy);
}
