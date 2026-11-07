package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateRollConventionsTest {

    @Test
    void following_moves_weekend_to_monday() {
        // 2024-06-01 是周六
        assertThat(DateRollConventions.all().require("FOLLOWING")
                .roll(LocalDate.of(2024, 6, 1))).isEqualTo(LocalDate.of(2024, 6, 3));
    }

    @Test
    void modified_following_stays_in_month() {
        // 2024-08-31 周六，向后会跨月 → 改向前
        assertThat(DateRollConventions.all().require("MODIFIED_FOLLOWING")
                .roll(LocalDate.of(2024, 8, 31))).isEqualTo(LocalDate.of(2024, 8, 30));
    }

    @Test
    void end_of_month_snaps_to_month_end() {
        assertThat(DateRollConventions.all().require("END_OF_MONTH")
                .roll(LocalDate.of(2024, 2, 10))).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void none_leaves_weekend_untouched() {
        LocalDate saturday = LocalDate.of(2024, 6, 1);
        assertThat(DateRollConventions.all().require("NONE").roll(saturday)).isEqualTo(saturday);
    }

    @Test
    void preceding_moves_weekend_to_friday() {
        assertThat(DateRollConventions.all().require("PRECEDING")
                .roll(LocalDate.of(2024, 6, 1))).isEqualTo(LocalDate.of(2024, 5, 31));
    }

    @Test
    void modified_preceding_rolls_forward_when_backward_crosses_month() {
        // 2024-09-01 是周日，向前会跨到 8 月 → 改为向后
        assertThat(DateRollConventions.all().require("MODIFIED_PRECEDING")
                .roll(LocalDate.of(2024, 9, 1))).isEqualTo(LocalDate.of(2024, 9, 2));
    }

    @Test
    void end_of_month_does_not_adjust_for_weekends() {
        // 2024-06-30 是周日，END_OF_MONTH 只对齐日，不避周末
        assertThat(DateRollConventions.all().require("END_OF_MONTH")
                .roll(LocalDate.of(2024, 6, 10))).isEqualTo(LocalDate.of(2024, 6, 30));
    }

    @Test
    void registry_exposes_all_six_codes() {
        assertThat(DateRollConventions.all().all())
                .extracting(roll -> roll.code())
                .containsExactlyInAnyOrder(
                        "NONE", "FOLLOWING", "MODIFIED_FOLLOWING",
                        "PRECEDING", "MODIFIED_PRECEDING", "END_OF_MONTH");
    }
}
