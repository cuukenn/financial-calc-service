package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.domain.port.RootSolver;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The default root solver: bisection keeps the bracket sign-opposite so it can never miss a root
 * of a continuous function, and a Newton step (numerical derivative) tries to shorten the bracket
 * faster. A rejected Newton step — one leaving the bracket, a vanishing derivative, or one that
 * barely contracts it — falls back to the plain midpoint.
 *
 * <p><b>The tolerance is a solution-domain promise.</b> {@code tolerance} bounds how far the
 * returned point may sit from a true root: the search stops when the sign-opposite bracket is that
 * wide, so the answer is within {@code tolerance / 2} of a root. Nothing else terminates the search.
 * An early exit on {@code |f(x)| <= tolerance} was this solver's original defect, because a rate is
 * dimensionless while an NPV is in yuan: the two numbers are not comparable, and on a flat NPV
 * (a sparse flow set, a near-degenerate deal) a function value of 1e-10 yuan can sit a whole
 * 1e-5 of rate away from the root. Accepting such a point would report an unconverged yield as a
 * converged one, which is the one thing a yield metric may not do (P-11). Only an exact
 * zero — a point that <em>is</em> the root — is accepted outside the bracket test.
 *
 * <p><b>Every iteration contracts.</b> A Newton step is accepted only when it lands in the inner
 * half of the bracket — at least a quarter of its width away from both ends — so whichever side it
 * closes, the sign-opposite bracket is left no wider than {@code 3/4} of what it was. A step that
 * would shave a sliver off one end (the classic stall: a steep function throwing the tangent almost
 * onto an endpoint) is rejected, and the midpoint splits the bracket in half instead. Without that
 * guard the budget can burn 100 iterations on a barely-moving bracket and the caller gets a
 * tolerance it was never owed; with it, {@code [-0.9999, 10]} converges to
 * {@link #DEFAULT_TOLERANCE} in at most 89 iterations, and the worst case is a pure bisection
 * ladder of 37.
 *
 * <p><b>Budget exhaustion never lies.</b> When the budget runs out the bracket is still
 * sign-opposite, so it holds a root and its width is at most {@code initial × 0.75^100}; the
 * midpoint is returned as the bracketed answer, exactly as {@link PmtSolver} does. {@code null} is
 * reserved for the one honest refusal: the bracket has no sign change, so there is nothing to
 * converge on and a reported rate would be fabricated.
 */
public final class BisectionNewtonSolver implements RootSolver {

    /** Iteration budget shared with the design document's solver convention. */
    public static final int MAX_ITERATIONS = 100;

    /**
     * Monotonic count of {@link #solve} entries since JVM start. Cost accounting only: a run must pay for each root exactly once, and a count a test can read
     * is how it proves that — never「看起来快了」as evidence.
     */
    public static final AtomicLong SOLVE_COUNT = new AtomicLong();

    /**
     * Monotonic count of function evaluations the solver has requested since JVM start, including
     * the endpoint probes, the Newton derivative probes and the {@link #bracketAnswer} polish: one
     * evaluation is one whole NPV/XNPV walk over the flow set. With {@link #SOLVE_COUNT} this is the
     * multiplication every consumer of the solver pays — solves × evaluations × flows.
     */
    public static final AtomicLong EVALUATION_COUNT = new AtomicLong();

    /** Default convergence target for yield metrics: the returned rate is within this of a root. */
    public static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("1e-10");

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal DERIVATIVE_H = new BigDecimal("1e-8");

    /**
     * The inner band a Newton candidate must land in, as a fraction of the bracket width: the
     * accepted bracket then shrinks to at most {@code 1 - 2 × 0.25 = 3/4} of its width.
     */
    private static final BigDecimal MIN_CONTRACTION = new BigDecimal("0.25");

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
        SOLVE_COUNT.incrementAndGet();
        Function<BigDecimal, BigDecimal> counted = rate -> {
            EVALUATION_COUNT.incrementAndGet();
            return function.apply(rate);
        };
        BigDecimal lo = lower;
        BigDecimal hi = upper;
        if (lo.compareTo(hi) > 0) {
            BigDecimal swap = lo;
            lo = hi;
            hi = swap;
        }
        BigDecimal fLo = counted.apply(lo);
        BigDecimal fHi = counted.apply(hi);
        if (fLo.signum() == 0) {
            return lo;
        }
        if (fHi.signum() == 0) {
            return hi;
        }
        if (fLo.signum() == fHi.signum()) {
            return null;
        }

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            BigDecimal mid = lo.add(hi).divide(TWO, MC);
            BigDecimal fMid = counted.apply(mid);
            if (fMid.signum() == 0) {
                return mid;
            }
            // The midpoint is the guaranteed-shrink update; a Newton step only replaces it when it
            // demonstrably contracts the bracket faster (see newtonStep). fMid is already evaluated
            // either way, so the fallback costs no extra evaluation.
            BigDecimal point = mid;
            BigDecimal fPoint = fMid;
            BigDecimal step = newtonStep(counted, mid, fMid, lo, hi);
            if (step != null) {
                BigDecimal fStep = counted.apply(step);
                if (fStep.signum() == 0) {
                    return step;
                }
                point = step;
                fPoint = fStep;
            }
            if (fPoint.signum() == fLo.signum()) {
                lo = point;
                fLo = fPoint;
            } else {
                hi = point;
                fHi = fPoint;
            }
            if (hi.subtract(lo).abs().compareTo(tolerance) <= 0) {
                return bracketAnswer(counted, lo, hi);
            }
        }
        // Budget exhausted: the bracket is still sign-opposite, so it holds a root and the
        // midpoint is within half its width of it — a bracketed answer, not a fabricated one. A
        // null here would report a non-convergence the sign invariant rules out.
        return bracketAnswer(counted, lo, hi);
    }

    /**
     * The answer for a bracket already narrow enough: its midpoint, polished by one Newton step.
     * The polish is accepted only when it stays inside the bracket <em>and</em> its function value
     * is no worse than the midpoint's, so it can only improve the answer — and either way the
     * returned point sits inside a sign-opposite bracket, which is where the accuracy promise
     * comes from. The polish is what keeps a flat function honest: near a root where the derivative
     * has collapsed, the midpoint of a tight bracket is still {@code tolerance / 2} away in the
     * rate domain, while one tangent usually lands within a rounding error of the root.
     */
    private BigDecimal bracketAnswer(Function<BigDecimal, BigDecimal> function, BigDecimal lo,
            BigDecimal hi) {
        BigDecimal mid = lo.add(hi).divide(TWO, MC);
        BigDecimal fMid = function.apply(mid);
        if (fMid.signum() == 0) {
            return mid;
        }
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
        return function.apply(step).abs().compareTo(fMid.abs()) <= 0 ? step : mid;
    }

    /**
     * Newton step from {@code mid} using the already-evaluated {@code fMid}; {@code null} when the
     * step is rejected — a vanishing derivative, one that would leave the bracket, or one landing
     * outside the inner band (a step that barely contracts the bracket) — in which case the caller
     * falls back to the midpoint.
     */
    private BigDecimal newtonStep(Function<BigDecimal, BigDecimal> function, BigDecimal mid,
            BigDecimal fMid, BigDecimal lo, BigDecimal hi) {
        BigDecimal scale = mid.abs().max(BigDecimal.ONE);
        BigDecimal h = DERIVATIVE_H.multiply(scale);
        BigDecimal derivative = function.apply(mid.add(h)).subtract(function.apply(mid.subtract(h)))
                .divide(h.multiply(TWO), MC);
        if (derivative.signum() == 0) {
            return null;
        }
        BigDecimal step = mid.subtract(fMid.divide(derivative, MC));
        if (step.compareTo(lo) <= 0 || step.compareTo(hi) >= 0) {
            return null;
        }
        BigDecimal margin = hi.subtract(lo).abs().multiply(MIN_CONTRACTION, MC);
        if (step.compareTo(lo.add(margin, MC)) < 0 || step.compareTo(hi.subtract(margin, MC)) > 0) {
            return null;
        }
        return step;
    }
}
