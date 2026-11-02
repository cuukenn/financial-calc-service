package com.fincalc.common.time;

import java.time.Instant;
import java.time.LocalDate;

/**
 * {@link ClockProvider} backed by the JVM system clock in the default time zone.
 */
public final class SystemClockProvider implements ClockProvider {

    /** Shared instance; the provider is stateless. */
    public static final SystemClockProvider INSTANCE = new SystemClockProvider();

    private SystemClockProvider() {
    }

    @Override
    public LocalDate today() {
        return LocalDate.now();
    }

    @Override
    public Instant now() {
        return Instant.now();
    }
}
