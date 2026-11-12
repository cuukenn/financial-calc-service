package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RootSolverTest {

    private final BisectionNewtonSolver solver = new BisectionNewtonSolver();

    @Test
    void solver_finds_root_of_simple_function() {
        // f(x) = x^2 - 4，根为 2
        Function<BigDecimal, BigDecimal> f = x -> x.multiply(x).subtract(new BigDecimal("4"));

        assertThat(solver.solve(f, BigDecimal.ONE, new BigDecimal("3"),
                new BigDecimal("1e-10")))
                        .isBetween(new BigDecimal("1.9999999999"), new BigDecimal("2.0000000001"));
    }

    @Test
    void solver_finds_root_of_monotone_function() {
        Function<BigDecimal, BigDecimal> f = x -> x.subtract(new BigDecimal("3"));

        assertThat(solver.solve(f, BigDecimal.ZERO, new BigDecimal("10"),
                new BigDecimal("1e-10")))
                        .isBetween(new BigDecimal("2.9999999999"), new BigDecimal("3.0000000001"));
    }

    @Test
    void solver_returns_bracket_endpoint_when_it_is_already_the_root() {
        Function<BigDecimal, BigDecimal> f = x -> x.subtract(BigDecimal.ONE);

        assertThat(solver.solve(f, BigDecimal.ONE, new BigDecimal("5"),
                new BigDecimal("1e-10"))).isEqualByComparingTo("1");
    }

    @Test
    void solver_returns_null_when_the_bracket_has_no_sign_change() {
        Function<BigDecimal, BigDecimal> f = x -> x.multiply(x).add(BigDecimal.ONE);

        assertThat(solver.solve(f, BigDecimal.ONE, new BigDecimal("3"),
                new BigDecimal("1e-10"))).isNull();
    }

    @Test
    void solver_converges_on_a_flattened_function_within_the_iteration_budget() {
        // f(x) = x^3 - 1e-9 on [0, 1]: the derivative near the root (1e-3) is 3e-6, so every Newton
        // step is nearly the midpoint and convergence is purely geometric. The guaranteed-shrink
        // invariant — an accepted step must land in the inner half of the bracket — is what makes
        // the budget provably enough for a 1e-25 rate tolerance.
        Function<BigDecimal, BigDecimal> f = x -> x.multiply(x).multiply(x)
                .subtract(new BigDecimal("1e-9"));

        assertThat(solver.solve(f, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("1e-25")))
                .isBetween(new BigDecimal("0.0009999999999999"),
                        new BigDecimal("0.0010000000000001"));
    }

    @Test
    void solver_keeps_a_budget_exhausted_answer_inside_the_sign_opposite_bracket() {
        // |f| can never fall below the function tolerance: it is 1e-20 on both sides of the jump,
        // and no midpoint of a bracket that never resolves the jump is within 1e-40 of zero. The
        // step function is outside the port's contract (it promises continuity), which is exactly
        // the point: the bracket is still sign-opposite, so a root is guaranteed inside it and the
        // honest answer is the bracket midpoint, not a null that claims nothing was found. A
        // fabricated rate is prevented by the bracket, not by refusing to answer.
        BigDecimal flat = new BigDecimal("1e-20");
        Function<BigDecimal, BigDecimal> f = x -> x.signum() >= 0 ? flat : flat.negate();

        assertThat(solver.solve(f, BigDecimal.ONE.negate(), BigDecimal.ONE, new BigDecimal("1e-40")))
                .isNotNull()
                .isBetween(BigDecimal.ONE.negate(), BigDecimal.ONE);
    }

    @Test
    void solver_exposes_extension_identity() {
        assertThat(solver.code()).isEqualTo("BISECTION_NEWTON");
        assertThat(solver.order()).isEqualTo(10);
    }
}
