package io.github.cuukenn.fincalc.domain.support;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Decimal-only transcendental helpers, shared by every metric that needs a non-integer power.
 *
 * <p>JDK 11's {@link BigDecimal} provides {@code pow(int)} only, and the design document forbids
 * {@code double} / {@code float} in any money or rate computation, so a decimal power is computed
 * as {@code exp(exponent · ln(base))} from these series. Keeping them in one class is what stops
 * the yield metrics, the effective-interest schedule and the remaining §9.5 metrics from each
 * growing their own copy.
 *
 * <p>Every series step runs at 40 significant digits — finer than {@link MathContext#DECIMAL128}
 * 's 34 — and the result is rounded once, so the truncation error (about 1e-38) stays invisible at
 * the {@code 1e-10} convergence tolerance the root solver uses.
 */
public final class DecimalMath {

    /** Internal precision of the transcendental steps. */
    private static final MathContext CALC = new MathContext(40, RoundingMode.HALF_EVEN);

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal SERIES_STOP = new BigDecimal("1e-36");
    private static final int ATANH_MAX_TERMS = 500;
    private static final int EXP_MAX_TERMS = 200;

    /** ln(2) taken from its own atanh series: {@code 2 · atanh(1/3)}. */
    private static final BigDecimal LN2 =
            atanhSeries(BigDecimal.ONE.divide(BigDecimal.valueOf(3), CALC)).multiply(TWO, CALC);

    private DecimalMath() {
    }

    /**
     * {@code base^exponent} for a positive base and any exponent, integer or fractional.
     *
     * @param base     the base, must be positive
     * @param exponent the exponent, may be fractional or negative
     * @return the power at the internal precision
     * @throws ArithmeticException when {@code base} is not positive
     */
    public static BigDecimal pow(BigDecimal base, BigDecimal exponent) {
        return exp(exponent.multiply(ln(base), CALC));
    }

    /**
     * Natural logarithm of a positive argument.
     *
     * @throws ArithmeticException when {@code value} is not positive
     */
    public static BigDecimal ln(BigDecimal value) {
        if (value.signum() <= 0) {
            throw new ArithmeticException("ln requires a positive argument: " + value.toPlainString());
        }
        BigDecimal mantissa = value;
        int exponent = 0;
        while (mantissa.compareTo(TWO) >= 0) {
            mantissa = mantissa.divide(TWO, CALC);
            exponent++;
        }
        while (mantissa.compareTo(BigDecimal.ONE) < 0) {
            mantissa = mantissa.multiply(TWO, CALC);
            exponent--;
        }
        // ln(m) = 2·atanh((m−1)/(m+1)) keeps |z| ≤ 1/3, which the series converges on quickly.
        BigDecimal z = mantissa.subtract(BigDecimal.ONE).divide(mantissa.add(BigDecimal.ONE), CALC);
        BigDecimal logMantissa = atanhSeries(z).multiply(TWO, CALC);
        return exponent == 0 ? logMantissa
                : logMantissa.add(LN2.multiply(BigDecimal.valueOf(exponent), CALC), CALC);
    }

    /** {@code e^y} via {@code 2^k · Σ r^n / n!} with {@code y = k·ln2 + r}, r kept near zero. */
    public static BigDecimal exp(BigDecimal y) {
        int k = y.divide(LN2, 0, RoundingMode.HALF_EVEN).intValueExact();
        BigDecimal r = y.subtract(LN2.multiply(BigDecimal.valueOf(k), CALC), CALC);
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal term = BigDecimal.ONE;
        for (int n = 1; n < EXP_MAX_TERMS; n++) {
            term = term.multiply(r, CALC).divide(BigDecimal.valueOf(n), CALC);
            sum = sum.add(term, CALC);
            if (term.abs().compareTo(SERIES_STOP) < 0) {
                break;
            }
        }
        return k >= 0 ? sum.multiply(TWO.pow(k), CALC) : sum.divide(TWO.pow(-k), CALC);
    }

    /** Σ z^(2n+1)/(2n+1); converges quickly for |z| ≤ 1/3. */
    private static BigDecimal atanhSeries(BigDecimal z) {
        BigDecimal sum = z;
        BigDecimal zSquared = z.multiply(z, CALC);
        BigDecimal term = z;
        for (int n = 1; n < ATANH_MAX_TERMS; n++) {
            term = term.multiply(zSquared, CALC);
            BigDecimal part = term.divide(BigDecimal.valueOf(2L * n + 1), CALC);
            sum = sum.add(part, CALC);
            if (part.abs().compareTo(SERIES_STOP) < 0) {
                break;
            }
        }
        return sum;
    }
}
