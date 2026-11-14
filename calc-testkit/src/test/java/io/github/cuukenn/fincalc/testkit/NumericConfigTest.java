package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.support.BisectionNewtonSolver;
import io.github.cuukenn.fincalc.domain.support.NumericConfig;
import io.github.cuukenn.fincalc.domain.support.PmtSolver;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import org.junit.jupiter.api.Test;

import io.github.cuukenn.fincalc.domain.model.Installment;

/**
 * {@code NumericConfig} 配置类：默认值逐项等于历史公开常量（默认路径逐位一致）；覆写
 * 真实贯通到求解器（逼近次数变化可观测），且不影响未覆写的维度。
 */
class NumericConfigTest {

    private static final CalculationFacade FACADE = CalculationFacade.defaults();
    private static final BigDecimal PRINCIPAL = new BigDecimal("1000000");
    private static final BigDecimal RATE = new BigDecimal("0.06");

    // ---- 默认值 = 历史公开常量 ----

    @Test
    void defaults_match_the_legacy_constants_exactly() {
        NumericConfig defaults = NumericConfig.defaults();
        assertThat(defaults.pmtMaxIterations()).isEqualTo(PmtSolver.MAX_ITERATIONS);
        assertThat(defaults.pmtBracketDoublings()).isEqualTo(PmtSolver.MAX_BRACKET_DOUBLINGS);
        assertThat(defaults.pmtTolerance()).isEqualByComparingTo(PmtSolver.TOLERANCE);
        assertThat(defaults.rootMaxIterations()).isEqualTo(BisectionNewtonSolver.MAX_ITERATIONS);
        assertThat(defaults.rootTolerance())
                .isEqualByComparingTo(BisectionNewtonSolver.DEFAULT_TOLERANCE);
        assertThat(defaults.closingTolerancePerPeriod()).isEqualByComparingTo("0.01");
    }

    // ---- 覆写真实贯通到求解器（行为可观测） ----

    @Test
    void solver_honors_the_configured_iteration_budget() {
        var function = (java.util.function.Function<BigDecimal, BigDecimal>)
                rate -> rate.pow(2).subtract(new BigDecimal("4"));
        // 默认预算：二分+牛顿收敛到精确根 2。
        assertThat(new BisectionNewtonSolver().solve(function, BigDecimal.ZERO,
                new BigDecimal("10"), new BigDecimal("1e-10")).subtract(new BigDecimal("2")).abs())
                .isLessThan(new BigDecimal("0.0000001"));
        // 预算 = 1：只有一次中点迭代，必然未收敛到根——配置真实到达了求解器。
        BigDecimal oneShot = new BisectionNewtonSolver(1).solve(function, BigDecimal.ZERO,
                new BigDecimal("10"), new BigDecimal("1e-10"));
        assertThat(oneShot.subtract(new BigDecimal("2")).abs())
                .isGreaterThan(new BigDecimal("0.0001"));
    }

    @Test
    void pmt_solver_honors_the_configured_budget_on_the_iterative_path() {
        // 混合费率窗口（31 天首月 + 30 天后三月）：等额租金必须二分逼近。
        List<BigDecimal> mixed = new java.util.ArrayList<>();
        mixed.add(new BigDecimal("0.0050959"));   // 31 天窗
        for (int i = 1; i < 4; i++) {
            mixed.add(new BigDecimal("0.005"));   // 30 天窗
        }
        BigDecimal defaultRent = PmtSolver.iterative(PRINCIPAL, mixed, RATE,
                BigDecimal.ZERO, NumericConfig.defaults());
        BigDecimal budgetOneRent = PmtSolver.iterative(PRINCIPAL, mixed, RATE,
                BigDecimal.ZERO, NumericConfig.builder().pmtMaxIterations(1).build());
        // 预算 = 1：一次中点迭代无法收敛到同一租金——配置真实贯通到求解器。
        assertThat(budgetOneRent).isNotEqualByComparingTo(defaultRent);
        assertThat(defaultRent).isNotNull();
    }

    // ---- 门面贯通：覆写 config 的运行成功且与默认路径结果可复算 ----

    @Test
    void facade_passes_the_config_through_and_default_path_stays_bit_identical() {
        LeaseElement golden = Fixtures
                .leaseElement(PRINCIPAL, RATE, 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360");
        CalculationResult plain = FACADE.calculate(
                new CalculationRequest(golden, null, CalculationOptions.defaults()));
        CalculationResult withDefaults = FACADE.calculate(
                new CalculationRequest(golden, null, CalculationOptions.defaults()),
                NumericConfig.defaults());
        // 默认 config 与不传 config：结果逐位一致（默认值 = 历史常量）。
        assertThat(withDefaults.schedule().header().metrics().find("TOTAL_RENT"))
                .isEqualByComparingTo(
                        plain.schedule().header().metrics().find("TOTAL_RENT"));
        // 覆写 config 的运行同样成功（黄金月频走闭式解，逼近次数不参与）。
        CalculationResult custom = FACADE.calculate(
                new CalculationRequest(golden, null, CalculationOptions.defaults()),
                NumericConfig.builder().rootMaxIterations(7).build());
        assertThat(custom.schedule().header().metrics().find("TOTAL_RENT"))
                .isEqualByComparingTo("1032797.15");
        assertThat(custom.schedule().hasErrors()).isFalse();
    }

    // ---- 非法配置快速失败 ----

    @Test
    void non_positive_iteration_budgets_are_rejected() {
        assertThatThrownBy(() -> NumericConfig.builder().pmtMaxIterations(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumericConfig.builder().pmtBracketDoublings(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumericConfig.builder().rootMaxIterations(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NumericConfig.builder().rootTolerance(null).build())
                .isInstanceOf(NullPointerException.class);
    }
}
