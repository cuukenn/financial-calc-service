package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import org.junit.jupiter.api.Test;

/**
 * 129号① 整月 + 零头拆分（P-14①；form-field-gaps ticket 05）：整月按月利率
 * （占比 1/12 每月）、零头按日利率（占比 1/360 每天），算头不算尾。
 */
class FirstPeriodStubSplitTest {

    @Test
    void an_aligned_month_is_exactly_one_twelfth() {
        BigDecimal yf = FirstPeriodStubSplit.yearFraction(LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 2, 1), PaymentFrequency.MONTHLY);
        assertThat(yf).isEqualByComparingTo(new BigDecimal("0.083333333333"));
    }

    @Test
    void a_short_window_is_days_only_and_excludes_the_end_date() {
        // 2024-01-01 → 2024-01-18：17 个实际天数（含 1 日、不含 18 日），无整月。
        BigDecimal yf = FirstPeriodStubSplit.yearFraction(LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 18), PaymentFrequency.MONTHLY);
        assertThat(yf).isEqualByComparingTo(new BigDecimal("0.047222222222"));
    }

    @Test
    void a_month_plus_days_window_splits_into_both_parts() {
        // 2024-01-01 → 2024-02-16：1 个整月 + 15 天 = 1/12 + 15/360。
        BigDecimal yf = FirstPeriodStubSplit.yearFraction(LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 2, 16), PaymentFrequency.MONTHLY);
        assertThat(yf).isEqualByComparingTo(new BigDecimal("0.125"));
    }

    @Test
    void full_months_walk_the_calendar_not_a_thirty_day_notion() {
        // 2024 是闰年：1-31 → 2-29 → 3-29 都存在，k = 2（整月）；3-29 → 3-30 余 1 天。
        // 2/12 + 1/360 = 0.169444…（30/360 口径会是 59/360 ≈ 0.163889 —— 月末边缘上 ①② 分叉）。
        BigDecimal yf = FirstPeriodStubSplit.yearFraction(LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 3, 30), PaymentFrequency.MONTHLY);
        assertThat(yf).isEqualByComparingTo(new BigDecimal("0.169444444445"));
    }

    @Test
    void quarterly_frequency_counts_months_by_the_calendar_too() {
        // 季频的一个整期 = 3 个月 = 3/12，与月频的三个整月同口径。
        BigDecimal yf = FirstPeriodStubSplit.yearFraction(LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 4, 1), PaymentFrequency.QUARTERLY);
        assertThat(yf).isEqualByComparingTo(new BigDecimal("0.25"));
    }

    @Test
    void irregular_frequency_has_no_month_anchor_and_is_rejected() {
        assertThatThrownBy(() -> FirstPeriodStubSplit.yearFraction(LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 2, 1), PaymentFrequency.IRREGULAR))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
