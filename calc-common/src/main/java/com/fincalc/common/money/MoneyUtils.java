package com.fincalc.common.money;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * Null-safe helpers for {@link BigDecimal} money arithmetic.
 */
public final class MoneyUtils {

    /** Zero amount. */
    public static final BigDecimal ZERO = BigDecimal.ZERO;

    /** One hundred, the basis for percentage conversions. */
    public static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private MoneyUtils() {
    }

    /**
     * Rescales {@code value}, rounding half up. A {@code null} value stays {@code null}.
     *
     * @param value the value to rescale, may be {@code null}
     * @param scale the target scale
     * @return the rescaled value, or {@code null} when {@code value} is {@code null}
     */
    public static BigDecimal scale(BigDecimal value, int scale) {
        if (value == null) {
            return null;
        }
        return value.setScale(scale, MoneyScale.ROUNDING);
    }

    /**
     * Rescales {@code value} to the output scale of 2, rounding half up.
     *
     * @param value the value to rescale, may be {@code null}
     * @return the value at scale 2, or {@code null} when {@code value} is {@code null}
     */
    public static BigDecimal out(BigDecimal value) {
        return scale(value, MoneyScale.OUTPUT);
    }

    /**
     * Rescales {@code value} to the intermediate scale of 10, rounding half up.
     *
     * @param value the value to rescale, may be {@code null}
     * @return the value at scale 10, or {@code null} when {@code value} is {@code null}
     */
    public static BigDecimal mid(BigDecimal value) {
        return scale(value, MoneyScale.INTERMEDIATE);
    }

    /**
     * Returns {@code value}, or {@link #ZERO} when it is {@code null}.
     *
     * @param value the value to guard, may be {@code null}
     * @return the value, or zero when {@code value} is {@code null}
     */
    public static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    /**
     * Sums the non-null values, starting from {@link #ZERO}. The scale of the result is the largest
     * scale among the summed values.
     *
     * @param values the values to sum, may be {@code null} entries
     * @return the sum, never {@code null}
     */
    public static BigDecimal sum(Collection<BigDecimal> values) {
        BigDecimal result = ZERO;
        for (BigDecimal value : values) {
            result = result.add(zeroIfNull(value));
        }
        return result;
    }

    /**
     * Compares two amounts with an absolute tolerance.
     *
     * @param a the first amount
     * @param b the second amount
     * @param tolerance the maximum allowed absolute difference
     * @return {@code true} when {@code |a - b| <= tolerance}
     */
    public static boolean eq(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return a.subtract(b).abs().compareTo(tolerance) <= 0;
    }
}
