package io.github.cuukenn.fincalc.domain.model;

import java.math.BigDecimal;

/**
 * One fee charged alongside the rent, such as a handling or consulting fee.
 */
public final class FeeItem {

    private final String type;
    private final BigDecimal amount;
    private final FeeChargeMode chargeMode;
    private final FeeOwner owner;
    private final boolean includeInIrr;

    private FeeItem(Builder builder) {
        this.type = builder.type;
        this.amount = builder.amount;
        this.chargeMode = builder.chargeMode;
        // 归一化在构造器：引擎读者永远拿到一个非空答案，未携带 owner 的既有要素保持
        // v1 姿态（承租人承担 = 出租人收取 = INFLOW）。
        this.owner = builder.owner == null ? FeeOwner.LESSEE : builder.owner;
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

    /**
     * Who bears the fee; never {@code null} (defaults to {@link FeeOwner#LESSEE}, the v1 posture).
     * The owner decides the fee row's direction on the lessor event table.
     */
    public FeeOwner owner() {
        return owner;
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
        private FeeOwner owner;
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

        /** Who bears the fee; {@code null} means the v1 posture (lessee-borne inflow). */
        public Builder owner(FeeOwner owner) {
            this.owner = owner;
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
