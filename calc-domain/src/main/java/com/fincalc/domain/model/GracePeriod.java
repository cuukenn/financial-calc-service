package com.fincalc.domain.model;

/**
 * Grace period at the start of the lease: periods where the borrower pays interest only,
 * or nothing at all.
 */
public final class GracePeriod {

    private final int interestOnlyPeriods;
    private final int fullGracePeriods;

    private GracePeriod(Builder builder) {
        this.interestOnlyPeriods = builder.interestOnlyPeriods;
        this.fullGracePeriods = builder.fullGracePeriods;
    }

    /** A grace period of zero length. */
    public static GracePeriod none() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Number of leading periods where only interest is paid. */
    public int interestOnlyPeriods() {
        return interestOnlyPeriods;
    }

    /** Number of leading periods where neither interest nor principal is paid. */
    public int fullGracePeriods() {
        return fullGracePeriods;
    }

    /** Builds {@link GracePeriod} instances. */
    public static final class Builder {

        private int interestOnlyPeriods;
        private int fullGracePeriods;

        private Builder() {
        }

        public Builder interestOnlyPeriods(int interestOnlyPeriods) {
            this.interestOnlyPeriods = interestOnlyPeriods;
            return this;
        }

        public Builder fullGracePeriods(int fullGracePeriods) {
            this.fullGracePeriods = fullGracePeriods;
            return this;
        }

        public GracePeriod build() {
            return new GracePeriod(this);
        }
    }
}
