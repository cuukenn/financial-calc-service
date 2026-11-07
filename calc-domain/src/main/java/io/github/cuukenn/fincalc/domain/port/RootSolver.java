package io.github.cuukenn.fincalc.domain.port;

import io.github.cuukenn.fincalc.common.spi.OrderedExtension;
import java.math.BigDecimal;
import java.util.function.Function;

/**
 * Pluggable root-finding strategy (design document §9.5): yield metrics are defined by an
 * equation — NPV(rate) = 0 — and the engine asks an extension to solve it, so the numeric method
 * can be substituted without touching the metric formulas.
 *
 * <p>The function is continuous on the requested bracket. The solver returns a point where
 * {@code |f|} is within {@code tolerance}, or {@code null} when the bracket holds no sign change
 * or the iteration budget is exhausted — a metric that does not converge is reported as
 * {@code null}, never as a fabricated number.
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
     * @return the root, an endpoint when it is already the root, or {@code null} when the bracket
     *         has no sign change or the budget is exhausted
     */
    BigDecimal solve(Function<BigDecimal, BigDecimal> function, BigDecimal lower, BigDecimal upper,
            BigDecimal tolerance);
}
