package com.fincalc.domain.model;

import java.math.BigDecimal;

/**
 * One fee charged alongside the rent, such as a handling or consulting fee.
 */
public final class FeeItem {

    private final String type;
    private final BigDecimal amount;
    private final FeeChargeMode chargeMode;
    private final BigDecimal vatRate;
    private final boolean includeInIrr;

    private FeeItem(Builder builder) {
        this.type = builder.type;
        this.amount = builder.amount;
        this.chargeMode = builder.chargeMode;
        this.vatRate = builder.vatRate;
        this.includeInIrr = builder.includeInIrr;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fee type such as "HANDLING" or "CONSULTING". */
    public String type() {
        return type;
    }

    /** Fee amount; never binary floating point. */
    public BigDecimal amount() {
        return amount;
    }

    /** How the fee is collected. */
    public FeeChargeMode chargeMode() {
        return chargeMode;
    }

    /** VAT rate applied to this fee; {@code null} means no separate VAT treatment. */
    public BigDecimal vatRate() {
        return vatRate;
    }

    /** Whether the fee participates in the IRR cash flow. */
    public boolean includeInIrr() {
        return includeInIrr;
    }

    /** Builds {@link FeeItem} instances. */
    public static final class Builder {

        private String type;
        private BigDecimal amount;
        private FeeChargeMode chargeMode;
        private BigDecimal vatRate;
        private boolean includeInIrr;

        private Builder() {
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder amount(BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        public Builder chargeMode(FeeChargeMode chargeMode) {
            this.chargeMode = chargeMode;
            return this;
        }

        public Builder vatRate(BigDecimal vatRate) {
            this.vatRate = vatRate;
            return this;
        }

        public Builder includeInIrr(boolean includeInIrr) {
            this.includeInIrr = includeInIrr;
            return this;
        }

        public FeeItem build() {
            return new FeeItem(this);
        }
    }
}
