package com.fincalc.domain.support;

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
    void solver_returns_null_when_the_iteration_budget_is_exhausted() {
        // |f| can never fall below the tolerance: 1e-20 on both sides of zero. The bracket halves
        // every iteration, so 100 iterations leave it about 1.6e-30 wide — still far wider than
        // 1e-40 — and design document §9.5 requires null rather than a fabricated rate.
        BigDecimal flat = new BigDecimal("1e-20");
        Function<BigDecimal, BigDecimal> f = x -> x.signum() >= 0 ? flat : flat.negate();

        assertThat(solver.solve(f, BigDecimal.ONE.negate(), BigDecimal.ONE, new BigDecimal("1e-40")))
                .isNull();
    }

    @Test
    void solver_exposes_extension_identity() {
        assertThat(solver.code()).isEqualTo("BISECTION_NEWTON");
        assertThat(solver.order()).isEqualTo(10);
    }
}
