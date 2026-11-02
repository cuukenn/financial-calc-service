package com.fincalc.common.money;

import java.math.RoundingMode;

/**
 * Precision and rounding constants shared by every money calculation.
 *
 * <p>All monetary values are represented as {@link java.math.BigDecimal}; binary floating point
 * types are never used.
 */
public final class MoneyScale {

    /** Scale for interest rates and day-count fractions. */
    public static final int CALC = 12;

    /** Scale for intermediate money amounts inside a calculation chain. */
    public static final int INTERMEDIATE = 10;

    /** Scale for monetary values returned to callers. */
    public static final int OUTPUT = 2;

    /** Rounding mode used for every scale reduction. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MoneyScale() {
    }
}
