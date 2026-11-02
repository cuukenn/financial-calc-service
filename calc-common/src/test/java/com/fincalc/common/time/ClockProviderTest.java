package com.fincalc.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ClockProviderTest {

    @Test
    void system_clock_provider_reports_current_time() {
        Instant before = Instant.now();
        LocalDate beforeDate = LocalDate.now();

        ClockProvider clock = SystemClockProvider.INSTANCE;
        Instant now = clock.now();
        LocalDate today = clock.today();

        LocalDate afterDate = LocalDate.now();
        Instant after = Instant.now();

        assertThat(now).isBetween(before, after);
        assertThat(today).isBetween(beforeDate, afterDate);
    }

    @Test
    void clock_provider_contract_can_be_stubbed() {
        Instant fixedInstant = Instant.parse("2024-01-31T00:00:00Z");
        LocalDate fixedDate = LocalDate.of(2024, 1, 31);
        ClockProvider fixed = new ClockProvider() {
            @Override
            public LocalDate today() {
                return fixedDate;
            }

            @Override
            public Instant now() {
                return fixedInstant;
            }
        };

        assertThat(fixed.today()).isEqualTo(fixedDate);
        assertThat(fixed.now()).isEqualTo(fixedInstant);
    }
}
