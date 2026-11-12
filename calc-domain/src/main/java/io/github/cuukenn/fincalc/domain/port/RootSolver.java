package io.github.cuukenn.fincalc.domain.port;

import io.github.cuukenn.fincalc.common.spi.OrderedExtension;
import java.math.BigDecimal;
import java.util.function.Function;

/**
 * Pluggable root-finding strategy (design document §9.5): yield metrics are defined by an
 * equation — NPV(rate) = 0 — and the engine asks an extension to solve it, so the numeric method
 * can be substituted without touching the metric formulas.
 *
 * <p>The function is continuous on the requested bracket. The {@code tolerance} argument is a
 * <em>solution-domain</em> tolerance: the returned point sits within it of a true root. The solver
 * returns {@code null} only when the bracket holds no sign change — a metric that has no root is
 * reported as {@code null}, never as a fabricated number; a budget exhaustion with the bracket
 * still sign-opposite returns the bracket midpoint instead, because the invariant guarantees a
 * root inside it.
 *
 * <p>When the function changes sign more than once the bracket contains several roots and the
 * solver returns whichever one its steps land on; it does not enumerate them. Design document §9.5
 * picks among several roots by preferring the smallest positive one nearest the element's nominal
 * rate, and a solver knows nothing about nominal rates — so that choice belongs to the caller that
 * assembles the metrics, not to this port.
 */
public interface RootSolver extends OrderedExtension {

    /**
     * Solves {@code f(x) = 0} for {@code x} in {@code [lower, upper]}.
     *
     * @return a point within {@code tolerance} of a root (an endpoint when it is already the
     *         root), or {@code null} when the bracket has no sign change
     */
    BigDecimal solve(Function<BigDecimal, BigDecimal> function, BigDecimal lower, BigDecimal upper,
            BigDecimal tolerance);
}
