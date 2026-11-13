package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import io.github.cuukenn.fincalc.domain.support.DateRollConventions;
import io.github.cuukenn.fincalc.domain.support.PmtSolver;
import io.github.cuukenn.fincalc.domain.support.daycount.Act360;
import io.github.cuukenn.fincalc.engine.GeneratorRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * AGENTS.md §6 N-05, ticket 04(b): the iterative rent path used to re-derive every window's year
 * fraction on every evaluation of the balance walk — a 1200-period {@code ACT_360} solve pays
 * roughly 150 evaluations, so one run re-derived the same 1200 quotients ~150 times over. The walk
 * now consumes a rate list derived once per solve ({@link PmtSolver#periodRates}); the wall clock
 * cannot show the saving at the 1200-period cell because the RESOLVE caliber's own walks dominate
 * it, but the count can: a whole uniform run must derive each window's fraction exactly twice —
 * once for the schedule row and once for the rent solve — and any evaluation that started
 * re-deriving its own rates would blow that bound by two orders of magnitude.
 */
class RateDerivationCountTest {

    @Test
    void the_iterative_path_derives_each_periods_rate_once_per_solve() {
        LeaseElement element = Fixtures.leaseElement(new BigDecimal("2380000"),
                new BigDecimal("0.035"), 1200, RepaymentMethodId.EQUAL_INSTALLMENT.name(),
                "ACT_360");
        CountingAct360 dayCount = new CountingAct360();
        CalculationContext context = new CalculationContext(element, null, dayCount,
                DateRollConventions.all().require(element.dateRollCode()),
                SystemClockProvider.INSTANCE, CalculationOptions.defaults());

        long solvesBefore = PmtSolver.SOLVE_COUNT.get();
        Schedule schedule = GeneratorRegistry.defaults()
                .require(RepaymentMethodId.EQUAL_INSTALLMENT.name())
                .generate(context);
        long solves = PmtSolver.SOLVE_COUNT.get() - solvesBefore;

        System.out.printf("[rate-derivation] 1200p ACT_360 uniform schedule: %d yearFraction "
                        + "derivations, %d rent solves%n", dayCount.yearFractions, solves);
        assertThat(schedule.installments()).hasSize(1200);
        assertThat(schedule.installments().get(1199).closingBalance())
                .as("the pinned shape must still be a closed run")
                .isEqualByComparingTo("0.00");
        assertThat(solves).as("a uniform schedule solves the rent once").isEqualTo(1);
        assertThat(dayCount.yearFractions)
                .as("1200 row derivations + one solve's rate list; a walk that re-derived "
                        + "its own rates would need ~150 × 1200")
                .isLessThanOrEqualTo(2L * element.periods());
    }

    /** Counting wrapper that keeps the {@code ACT_360} code so the context resolves to it. */
    private static final class CountingAct360 implements DayCountConvention {

        private final Act360 delegate = new Act360();
        private long yearFractions;

        @Override
        public String code() {
            return delegate.code();
        }

        @Override
        public int order() {
            return delegate.order();
        }

        @Override
        public int days(LocalDate start, LocalDate end) {
            return delegate.days(start, end);
        }

        @Override
        public BigDecimal yearFraction(LocalDate start, LocalDate end) {
            yearFractions++;
            return delegate.yearFraction(start, end);
        }
    }
}
