package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.MathContext;
import org.junit.jupiter.api.Test;

/**
 * Goldens for the decimal transcendental helpers are computed independently (correctly rounded
 * decimal arithmetic at 45 digits), not by the implementation under test.
 */
class DecimalMathTest {

    private static final BigDecimal EPS = new BigDecimal("1e-25");

    @Test
    void pow_with_integer_exponent_matches_bigdecimal_pow() {
        BigDecimal base = new BigDecimal("1.005");
        // exact: 1.061677811864499568789707617431640625
        assertThat(DecimalMath.pow(base, new BigDecimal("12")))
                .isCloseTo(new BigDecimal("1.061677811864499568789707617431640625"), within(EPS));
    }

    @Test
    void pow_with_fractional_exponent_covers_roots_and_reciprocals() {
        assertThat(DecimalMath.pow(new BigDecimal("4"), new BigDecimal("0.5")))
                .isCloseTo(BigDecimal.valueOf(2), within(EPS));
        assertThat(DecimalMath.pow(new BigDecimal("4"), new BigDecimal("-0.5")))
                .isCloseTo(new BigDecimal("0.5"), within(EPS));
    }

    @Test
    void pow_supports_the_day_fraction_exponents_used_by_x_metrics() {
        BigDecimal exponent = BigDecimal.valueOf(365).divide(BigDecimal.valueOf(366),
                MathContext.DECIMAL128);
        assertThat(DecimalMath.pow(new BigDecimal("1.1"), exponent))
                .isCloseTo(new BigDecimal("1.099713585934141241287216920352387"), within(EPS));

        BigDecimal halfYear = BigDecimal.valueOf(182).divide(BigDecimal.valueOf(365),
                MathContext.DECIMAL128);
        assertThat(DecimalMath.pow(new BigDecimal("1.1"), halfYear))
                .isCloseTo(new BigDecimal("1.048671922643358143723633709100602"), within(EPS));
    }

    @Test
    void pow_of_a_rate_close_to_zero_stays_accurate() {
        assertThat(DecimalMath.pow(new BigDecimal("1.0001"), new BigDecimal("-36")))
                .isCloseTo(new BigDecimal("0.996406651572218524413691616576518"), within(EPS));
    }

    @Test
    void pow_degenerates_to_one() {
        assertThat(DecimalMath.pow(BigDecimal.ONE, new BigDecimal("7.5")))
                .isCloseTo(BigDecimal.ONE, within(EPS));
        assertThat(DecimalMath.pow(new BigDecimal("3.7"), BigDecimal.ZERO))
                .isCloseTo(BigDecimal.ONE, within(EPS));
    }

    @Test
    void ln_and_exp_match_their_pinned_reference_values() {
        assertThat(DecimalMath.ln(BigDecimal.valueOf(2)))
                .isCloseTo(new BigDecimal("0.6931471805599453094172321214581766"), within(EPS));
        assertThat(DecimalMath.exp(BigDecimal.ONE))
                .isCloseTo(new BigDecimal("2.7182818284590452353602874713526625"), within(EPS));
    }

    @Test
    void ln_rejects_a_non_positive_argument() {
        assertThatThrownBy(() -> DecimalMath.ln(BigDecimal.ZERO))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> DecimalMath.ln(new BigDecimal("-0.5")))
                .isInstanceOf(ArithmeticException.class);
    }
}
