package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.money.MoneyTolerance;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 引擎数值求解配置（不可变）：把此前写死在求解器类里的逼近次数、容差与求根区间收敛为
 * 一个显式配置对象。
 *
 * <p><b>默认值即现行值</b>：{@link #defaults()} 逐项等于各求解器类的历史公开常量
 * （{@code PmtSolver.MAX_ITERATIONS/TOLERANCE}、{@code BisectionNewtonSolver.MAX_ITERATIONS/
 * DEFAULT_TOLERANCE}、{@code IrrCalculator.RATE_LOWER/UPPER}、
 * {@code EffectiveInterestCalculator} 的每期闭合容差）。默认路径与历史行为逐位一致
 * （AGENTS.md 逐位一致纪律）；覆写仅用于特殊指标的求解收敛性调参，不构成口径变更。
 *
 * <p>不可变且线程安全；经 {@code CalculationContext}/{@code MetricContext} 贯通到
 * 求解器与指标层，入口为 {@code CalculationFacade.calculate(request, config)}。
 */
public final class NumericConfig {

    /** PMT 二分逼近的最大迭代次数（默认 = PmtSolver.MAX_ITERATIONS）。 */
    private final int pmtMaxIterations;

    /** PMT 租金 bracket 倍扩预算（默认 = PmtSolver.MAX_BRACKET_DOUBLINGS）。 */
    private final int pmtBracketDoublings;

    /** PMT 二分逼近的收敛容差（默认 = PmtSolver.TOLERANCE）。 */
    private final BigDecimal pmtTolerance;

    /** 求根器（二分-牛顿）的最大迭代次数（默认 = BisectionNewtonSolver.MAX_ITERATIONS）。 */
    private final int rootMaxIterations;

    /** 求根器的收敛容差（默认 = BisectionNewtonSolver.DEFAULT_TOLERANCE）。 */
    private final BigDecimal rootTolerance;

    /** IRR/XIRR 求根区间下界（默认 = IrrCalculator.RATE_LOWER）。 */
    private final BigDecimal rootLowerBound;

    /** IRR/XIRR 求根区间上界（默认 = IrrCalculator.RATE_UPPER）。 */
    private final BigDecimal rootUpperBound;

    /** §9.7 实际利率法表每期闭合容差（默认 = MoneyTolerance.MONEY_COMPARE）。 */
    private final BigDecimal closingTolerancePerPeriod;

    private NumericConfig(Builder builder) {
        this.pmtMaxIterations = builder.pmtMaxIterations;
        this.pmtBracketDoublings = builder.pmtBracketDoublings;
        this.pmtTolerance = builder.pmtTolerance;
        this.rootMaxIterations = builder.rootMaxIterations;
        this.rootTolerance = builder.rootTolerance;
        this.rootLowerBound = builder.rootLowerBound;
        this.rootUpperBound = builder.rootUpperBound;
        this.closingTolerancePerPeriod = builder.closingTolerancePerPeriod;
    }

    /** 默认配置：逐项等于各求解器类的历史公开常量，行为与写死版本逐位一致。 */
    public static NumericConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int pmtMaxIterations() {
        return pmtMaxIterations;
    }

    public int pmtBracketDoublings() {
        return pmtBracketDoublings;
    }

    public BigDecimal pmtTolerance() {
        return pmtTolerance;
    }

    public int rootMaxIterations() {
        return rootMaxIterations;
    }

    public BigDecimal rootTolerance() {
        return rootTolerance;
    }

    public BigDecimal rootLowerBound() {
        return rootLowerBound;
    }

    public BigDecimal rootUpperBound() {
        return rootUpperBound;
    }

    public BigDecimal closingTolerancePerPeriod() {
        return closingTolerancePerPeriod;
    }

    /** Builder：未设置的项取默认值。 */
    public static final class Builder {
        private int pmtMaxIterations = PmtSolver.MAX_ITERATIONS;
        private int pmtBracketDoublings = PmtSolver.MAX_BRACKET_DOUBLINGS;
        private BigDecimal pmtTolerance = PmtSolver.TOLERANCE;
        private int rootMaxIterations = BisectionNewtonSolver.MAX_ITERATIONS;
        private BigDecimal rootTolerance = BisectionNewtonSolver.DEFAULT_TOLERANCE;
        private BigDecimal rootLowerBound = IrrCalculator.RATE_LOWER;
        private BigDecimal rootUpperBound = IrrCalculator.RATE_UPPER;
        private BigDecimal closingTolerancePerPeriod = MoneyTolerance.MONEY_COMPARE;

        private Builder() {
        }

        public Builder pmtMaxIterations(int pmtMaxIterations) {
            if (pmtMaxIterations < 1) {
                throw new IllegalArgumentException("pmtMaxIterations must be at least 1");
            }
            this.pmtMaxIterations = pmtMaxIterations;
            return this;
        }

        public Builder pmtBracketDoublings(int pmtBracketDoublings) {
            if (pmtBracketDoublings < 1) {
                throw new IllegalArgumentException("pmtBracketDoublings must be at least 1");
            }
            this.pmtBracketDoublings = pmtBracketDoublings;
            return this;
        }

        public Builder pmtTolerance(BigDecimal pmtTolerance) {
            this.pmtTolerance = Objects.requireNonNull(pmtTolerance, "pmtTolerance");
            return this;
        }

        public Builder rootMaxIterations(int rootMaxIterations) {
            if (rootMaxIterations < 1) {
                throw new IllegalArgumentException("rootMaxIterations must be at least 1");
            }
            this.rootMaxIterations = rootMaxIterations;
            return this;
        }

        public Builder rootTolerance(BigDecimal rootTolerance) {
            this.rootTolerance = Objects.requireNonNull(rootTolerance, "rootTolerance");
            return this;
        }

        public Builder rootLowerBound(BigDecimal rootLowerBound) {
            this.rootLowerBound = Objects.requireNonNull(rootLowerBound, "rootLowerBound");
            return this;
        }

        public Builder rootUpperBound(BigDecimal rootUpperBound) {
            this.rootUpperBound = Objects.requireNonNull(rootUpperBound, "rootUpperBound");
            return this;
        }

        public Builder closingTolerancePerPeriod(BigDecimal closingTolerancePerPeriod) {
            this.closingTolerancePerPeriod = Objects.requireNonNull(closingTolerancePerPeriod,
                    "closingTolerancePerPeriod");
            return this;
        }

        public NumericConfig build() {
            return new NumericConfig(this);
        }
    }
}
