package com.fincalc.domain.model;

import java.time.LocalDate;

/**
 * One explicitly given period of an {@link PaymentFrequency#IRREGULAR} schedule.
 */
public final class PeriodDate {

    private final LocalDate startDate;
    private final LocalDate endDate;
    private final LocalDate paymentDate;

    private PeriodDate(Builder builder) {
        this.startDate = builder.startDate;
        this.endDate = builder.endDate;
        this.paymentDate = builder.paymentDate;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** First day of the period (inclusive). */
    public LocalDate startDate() {
        return startDate;
    }

    /** End of the period, exclusive: the first day of the next period. */
    public LocalDate endDate() {
        return endDate;
    }

    /** Date the rent for this period is paid. */
    public LocalDate paymentDate() {
        return paymentDate;
    }

    /** Builds {@link PeriodDate} instances. */
    public static final class Builder {

        private LocalDate startDate;
        private LocalDate endDate;
        private LocalDate paymentDate;

        private Builder() {
        }

        public Builder startDate(LocalDate startDate) {
            this.startDate = startDate;
            return this;
        }

        public Builder endDate(LocalDate endDate) {
            this.endDate = endDate;
            return this;
        }

        public Builder paymentDate(LocalDate paymentDate) {
            this.paymentDate = paymentDate;
            return this;
        }

        public PeriodDate build() {
            return new PeriodDate(this);
        }
    }
}
