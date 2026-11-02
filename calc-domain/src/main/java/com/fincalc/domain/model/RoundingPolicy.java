package com.fincalc.domain.model;

import java.math.RoundingMode;

import com.fincalc.common.money.MoneyScale;

/**
 * Precision and tail-rounding policy of a calculation.
 */
public final class RoundingPolicy {

    private final int scale;
    private final RoundingMode mode;
    private final TailAdjustPolicy tailAdjustPolicy;

    private RoundingPolicy(Builder builder) {
        this.scale = builder.scale;
        this.mode = builder.mode;
        this.tailAdjustPolicy = builder.tailAdjustPolicy;
    }

    /**
     * The default policy: output scale ({@link MoneyScale#OUTPUT}), the shared rounding mode
     * ({@link MoneyScale#ROUNDING}) and tail adjustment on the last period.
     */
    public static RoundingPolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Scale money amounts are rounded to. */
    public int scale() {
        return scale;
    }

    /** Rounding mode applied when scaling. */
    public RoundingMode mode() {
        return mode;
    }

    /** Where a remaining rounding difference is absorbed. */
    public TailAdjustPolicy tailAdjustPolicy() {
        return tailAdjustPolicy;
    }

    /** Builds {@link RoundingPolicy} instances; starts from {@link #defaults()} values. */
    public static final class Builder {

        private int scale = MoneyScale.OUTPUT;
        private RoundingMode mode = MoneyScale.ROUNDING;
        private TailAdjustPolicy tailAdjustPolicy = TailAdjustPolicy.LAST_PERIOD;

        private Builder() {
        }

        public Builder scale(int scale) {
            this.scale = scale;
            return this;
        }

        public Builder mode(RoundingMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder tailAdjustPolicy(TailAdjustPolicy tailAdjustPolicy) {
            this.tailAdjustPolicy = tailAdjustPolicy;
            return this;
        }

        public RoundingPolicy build() {
            return new RoundingPolicy(this);
        }
    }
}
