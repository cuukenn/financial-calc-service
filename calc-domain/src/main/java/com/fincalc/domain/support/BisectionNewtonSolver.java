package com.fincalc.domain.support;

import com.fincalc.domain.port.RootSolver;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.function.Function;

/**
 * The default root solver: bisection keeps the bracket sign-opposite so it can never miss a root
 * of a continuous function, and a Newton step (numerical derivative) tries to shorten the bracket
 * faster. A rejected Newton step — one leaving the bracket, or a vanishing derivative — falls back
 * to the plain midpoint, so the guaranteed shrink remains in force under the 100-iteration budget.
 */
public final class BisectionNewtonSolver implements RootSolver {

    /** Iteration budget shared with the design document's solver convention. */
    public static final int MAX_ITERATIONS = 100;

    /** Default convergence target for yield metrics. */
    public static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("1e-10");

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal DERIVATIVE_H = new BigDecimal("1e-8");

    @Override
    public String code() {
        return "BISECTION_NEWTON";
    }

    @Override
    public int order() {
        return 10;
    }

    /** Convenience entry with the default tolerance. */
    public BigDecimal solve(Function<BigDecimal, BigDecimal> function, BigDecimal lower,
            BigDecimal upper) {
        return solve(function, lower, upper, DEFAULT_TOLERANCE);
    }

    @Override
    public BigDecimal solve(Function<BigDecimal, BigDecimal> function, BigDecimal lower,
            BigDecimal upper, BigDecimal tolerance) {
        BigDecimal lo = lower;
        BigDecimal hi = upper;
        if (lo.compareTo(hi) > 0) {
            BigDecimal swap = lo;
            lo = hi;
            hi = swap;
        }

        BigDecimal fLo = function.apply(lo);
        BigDecimal fHi = function.apply(hi);
        if (within(fLo, tolerance)) {
            return lo;
        }
        if (within(fHi, tolerance)) {
            return hi;
        }
        if (fLo.signum() == fHi.signum()) {
            return null;
        }

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            BigDecimal mid = lo.add(hi).divide(TWO, MC);
            BigDecimal point = newtonPoint(function, mid, lo, hi);
            BigDecimal fPoint = function.apply(point);
            if (within(fPoint, tolerance)) {
                return point;
            }
            if (fPoint.signum() == fLo.signum()) {
                lo = point;
                fLo = fPoint;
            } else {
                hi = point;
                fHi = fPoint;
            }
            if (hi.subtract(lo).abs().compareTo(tolerance) <= 0) {
                return point;
            }
        }
        // Budget exhausted: the bracket is still sign-opposite but not within tolerance.
        return null;
    }

    /** Newton step from {@code mid}; any step that leaves the bracket falls back to the midpoint. */
    private BigDecimal newtonPoint(Function<BigDecimal, BigDecimal> function, BigDecimal mid,
            BigDecimal lo, BigDecimal hi) {
        BigDecimal fMid = function.apply(mid);
        BigDecimal scale = mid.abs().max(BigDecimal.ONE);
        BigDecimal h = DERIVATIVE_H.multiply(scale);
        BigDecimal derivative = function.apply(mid.add(h)).subtract(function.apply(mid.subtract(h)))
                .divide(h.multiply(TWO), MC);
        if (derivative.signum() == 0) {
            return mid;
        }
        BigDecimal step = mid.subtract(fMid.divide(derivative, MC));
        if (step.compareTo(lo) <= 0 || step.compareTo(hi) >= 0) {
            return mid;
        }
        return step;
    }

    private static boolean within(BigDecimal value, BigDecimal tolerance) {
        return value.abs().compareTo(tolerance) <= 0;
    }
}
