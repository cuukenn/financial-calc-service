package io.github.cuukenn.fincalc.common.date;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateUtilsTest {

    @Test
    void endOfMonth_returns_last_day_of_month() {
        assertThat(DateUtils.endOfMonth(LocalDate.of(2024, 1, 15)))
                .isEqualTo(LocalDate.of(2024, 1, 31));
        assertThat(DateUtils.endOfMonth(LocalDate.of(2023, 2, 1)))
                .isEqualTo(LocalDate.of(2023, 2, 28));
        assertThat(DateUtils.endOfMonth(LocalDate.of(2024, 2, 1)))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void isEndOfMonth_detects_last_day_of_month() {
        assertThat(DateUtils.isEndOfMonth(LocalDate.of(2024, 1, 31))).isTrue();
        assertThat(DateUtils.isEndOfMonth(LocalDate.of(2024, 2, 29))).isTrue();
        assertThat(DateUtils.isEndOfMonth(LocalDate.of(2024, 1, 30))).isFalse();
        assertThat(DateUtils.isEndOfMonth(LocalDate.of(2023, 2, 28))).isTrue();
        assertThat(DateUtils.isEndOfMonth(LocalDate.of(2023, 2, 27))).isFalse();
    }

    @Test
    void plusMonthsKeepEom_clamps_31st_to_target_month_end() {
        assertThat(DateUtils.plusMonthsKeepEom(LocalDate.of(2024, 1, 31), 1))
                .isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(DateUtils.plusMonthsKeepEom(LocalDate.of(2023, 1, 31), 1))
                .isEqualTo(LocalDate.of(2023, 2, 28));
        assertThat(DateUtils.plusMonthsKeepEom(LocalDate.of(2024, 2, 29), 12))
                .isEqualTo(LocalDate.of(2025, 2, 28));
    }

    @Test
    void daysBetween_is_half_open() {
        assertThat(DateUtils.daysBetween(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 1))).isEqualTo(60);
    }
}
