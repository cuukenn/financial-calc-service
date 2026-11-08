package io.github.cuukenn.fincalc.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneyToleranceTest {

    @Test
    void money_compare_is_one_cent_at_the_output_scale() {
        assertThat(MoneyTolerance.MONEY_COMPARE).isEqualByComparingTo("0.01");
        assertThat(MoneyTolerance.MONEY_COMPARE.scale()).isEqualTo(MoneyScale.OUTPUT);
    }
}
