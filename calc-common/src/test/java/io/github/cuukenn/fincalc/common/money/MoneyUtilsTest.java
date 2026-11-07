package io.github.cuukenn.fincalc.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MoneyUtilsTest {

    @Test
    void out_rounds_half_up_to_two_decimals() {
        assertThat(MoneyUtils.out(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
        assertThat(MoneyUtils.out(new BigDecimal("1.004"))).isEqualByComparingTo("1.00");
    }

    @Test
    void sum_ignores_null_and_keeps_scale() {
        assertThat(MoneyUtils.sum(Arrays.asList(new BigDecimal("1.10"), null, new BigDecimal("2.20"))))
                .isEqualByComparingTo("3.30");
    }

    @Test
    void eq_uses_absolute_tolerance() {
        assertThat(MoneyUtils.eq(new BigDecimal("100.00"), new BigDecimal("100.01"), new BigDecimal("0.01")))
                .isTrue();
    }
}
