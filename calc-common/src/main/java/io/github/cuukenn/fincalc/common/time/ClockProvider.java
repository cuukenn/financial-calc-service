package io.github.cuukenn.fincalc.common.time;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Port for the current date and time, so that callers never read the system clock directly and
 * tests can substitute a fixed clock.
 */
public interface ClockProvider {

    /**
     * Returns the current date in the provider's time zone.
     *
     * @return the current date
     */
    LocalDate today();

    /**
     * Returns the current instant.
     *
     * @return the current instant
     */
    Instant now();
}
