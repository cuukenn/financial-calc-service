package io.github.cuukenn.fincalc.domain.model;

/**
 * How often rent falls due within a year.
 */
public enum PaymentFrequency {

    /** Rent every month. */
    MONTHLY(1, true),

    /** Rent every three months. */
    QUARTERLY(3, true),

    /** Rent every six months. */
    SEMI_ANNUAL(6, true),

    /** Rent every twelve months. */
    ANNUAL(12, true),

    /**
     * Schedule without a fixed period length; the periods are given explicitly through
     * {@link LeaseElement} instead of being derived from the period count.
     */
    IRREGULAR(0, false);

    private static final int MONTHS_PER_YEAR = 12;

    private final int monthsPerPeriod;
    private final boolean regular;

    PaymentFrequency(int monthsPerPeriod, boolean regular) {
        this.monthsPerPeriod = monthsPerPeriod;
        this.regular = regular;
    }

    /** Number of months covered by one payment period. */
    public int monthsPerPeriod() {
        return monthsPerPeriod;
    }

    /**
     * Number of payment periods per year: 12, 4, 2 and 1 for the regular frequencies,
     * and 0 for {@link #IRREGULAR}.
     */
    public int periodsPerYear() {
        return regular ? MONTHS_PER_YEAR / monthsPerPeriod : 0;
    }

    /** Whether the schedule has a fixed period length. */
    public boolean isRegular() {
        return regular;
    }
}
