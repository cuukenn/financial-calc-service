package com.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fincalc.domain.port.DayCountConvention;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DayCountConventionsTest {

    @Test
    void act360_uses_actual_days_over_360() {
        DayCountConvention c = DayCountConventions.all().require("ACT_360");
        assertThat(c.days(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 3, 1))).isEqualTo(29);
        assertThat(c.yearFraction(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)))
                .isEqualByComparingTo(new BigDecimal("1.016666666667"));
    }

    @Test
    void thirty360_clamps_day31_to_30() {
        DayCountConvention c = DayCountConventions.all().require("THIRTY_360");
        assertThat(c.days(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29))).isEqualTo(29);
        assertThat(c.days(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1))).isEqualTo(30);
    }

    @Test
    void act_act_splits_by_calendar_year() {
        DayCountConvention c = DayCountConventions.all().require("ACT_ACT");
        assertThat(c.yearFraction(LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)))
                .isEqualByComparingTo(new BigDecimal("1.000000000000"));
        assertThat(c.yearFraction(LocalDate.of(2024, 7, 1), LocalDate.of(2025, 7, 1)))
                .isEqualByComparingTo(new BigDecimal("0.998622651396"));
    }

    @Test
    void registers_all_six_codes() {
        assertThat(DayCountConventions.all().all())
                .extracting(DayCountConvention::code)
                .containsExactlyInAnyOrder(
                        "ACT_360", "ACT_365F", "ACT_ACT", "THIRTY_360", "THIRTY_E_360", "ACT_365_25");
    }

    @Test
    void euro_thirty_clamps_both_endpoints() {
        DayCountConvention c = DayCountConventions.all().require("THIRTY_E_360");
        assertThat(c.days(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 3, 31))).isEqualTo(60);
    }
}
