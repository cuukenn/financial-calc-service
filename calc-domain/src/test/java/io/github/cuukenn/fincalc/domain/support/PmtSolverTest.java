package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import io.github.cuukenn.fincalc.domain.support.daycount.Act365F;
import io.github.cuukenn.fincalc.domain.support.daycount.ActAct;
import io.github.cuukenn.fincalc.domain.support.daycount.Thirty360;
import io.github.cuukenn.fincalc.domain.support.daycount.ThirtyE360;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PmtSolverTest {

    private static final BigDecimal PRINCIPAL = new BigDecimal("1000000");
    private static final BigDecimal ANNUAL_RATE = new BigDecimal("0.06");
    private static final BigDecimal MONTHLY_RATE = new BigDecimal("0.005");
    private static final BigDecimal GOLDEN_PMT = new BigDecimal("86066.43");

    /** Half a cent: the width inside which the solver promises to have found the exact rent. */
    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

    @Test
    void closed_form_matches_reference() {
        assertThat(PmtSolver.closedForm(PRINCIPAL, MONTHLY_RATE, 12, BigDecimal.ZERO))
                .isEqualByComparingTo("86066.43");
    }

    @Test
    void closed_form_handles_zero_rate() {
        assertThat(PmtSolver.closedForm(new BigDecimal("1200000"), BigDecimal.ZERO, 12, BigDecimal.ZERO))
                .isEqualByComparingTo("100000.00");
    }

    @Test
    void closed_form_handles_single_period() {
        assertThat(PmtSolver.closedForm(PRINCIPAL, MONTHLY_RATE, 1, BigDecimal.ZERO))
                .isEqualByComparingTo("1005000.00");
    }

    @Test
    void closed_form_with_residual_solves_balloon() {
        BigDecimal pmt = PmtSolver.closedForm(PRINCIPAL, MONTHLY_RATE, 12, new BigDecimal("100000"));

        assertThat(pmt).isLessThan(GOLDEN_PMT);
        assertRentIsCentExact(pmt, new BigDecimal("100000"));
    }

    @Test
    void closed_form_closes_an_annuity_immediate_at_zero() {
        BigDecimal pmt = PmtSolver.closedForm(PRINCIPAL, MONTHLY_RATE, 12, BigDecimal.ZERO);

        assertRentIsCentExact(pmt, BigDecimal.ZERO);
    }

    @Test
    void closed_form_rejects_a_non_positive_period_count() {
        assertThatThrownBy(() -> PmtSolver.closedForm(PRINCIPAL, MONTHLY_RATE, 0, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void is_constant_period_rate_by_convention() {
        assertThat(PmtSolver.isConstantPeriodRate(new Thirty360())).isTrue();
        assertThat(PmtSolver.isConstantPeriodRate(new ThirtyE360())).isTrue();
        assertThat(PmtSolver.isConstantPeriodRate(new Act365F())).isFalse();
        assertThat(PmtSolver.isConstantPeriodRate(new ActAct())).isFalse();
    }

    @Test
    void iterative_closes_the_schedule_under_act_windows() {
        List<PeriodDate> windows = monthlyWindows("2024-01-01", 12);
        Act365F dayCount = new Act365F();

        BigDecimal rent = PmtSolver.iterative(PRINCIPAL, windows, dayCount, ANNUAL_RATE, BigDecimal.ZERO);

        assertRentIsCentExact(rent, windows, dayCount, BigDecimal.ZERO);
    }

    @Test
    void iterative_matches_closed_form_when_windows_are_uniform() {
        List<PeriodDate> windows = monthlyWindows("2024-01-01", 12);
        Thirty360 dayCount = new Thirty360();

        BigDecimal rent = PmtSolver.iterative(PRINCIPAL, windows, dayCount, ANNUAL_RATE, BigDecimal.ZERO);

        assertThat(rent).isEqualByComparingTo(GOLDEN_PMT);
        assertRentIsCentExact(rent, windows, dayCount, BigDecimal.ZERO);
    }

    @Test
    void iterative_leaves_a_balloon_residual_outstanding() {
        List<PeriodDate> windows = monthlyWindows("2024-01-01", 12);
        Act365F dayCount = new Act365F();
        BigDecimal residual = new BigDecimal("100000");

        BigDecimal rent = PmtSolver.iterative(PRINCIPAL, windows, dayCount, ANNUAL_RATE, residual);

        assertThat(rent).isLessThan(GOLDEN_PMT);
        assertRentIsCentExact(rent, windows, dayCount, residual);
    }

    @Test
    void iterative_handles_zero_rate() {
        List<PeriodDate> windows = monthlyWindows("2024-01-01", 12);
        Act365F dayCount = new Act365F();

        BigDecimal rent = PmtSolver.iterative(new BigDecimal("1200000"), windows, dayCount,
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(rent).isEqualByComparingTo("100000");
    }

    @Test
    void iterative_needs_no_more_than_the_iteration_cap() {
        List<PeriodDate> windows = monthlyWindows("2024-01-01", 360);
        Act365F dayCount = new Act365F();

        BigDecimal rent = PmtSolver.iterative(PRINCIPAL, windows, dayCount, ANNUAL_RATE, BigDecimal.ZERO);

        assertRentIsCentExact(rent, windows, dayCount, BigDecimal.ZERO);
    }

    /**
     * The rent is the exact solution rounded to the shared output scale, so moving it by half a cent
     * in either direction must land on opposite sides of the target balance.
     */
    private static void assertRentIsCentExact(BigDecimal rent, List<PeriodDate> windows,
            DayCountConvention dayCount, BigDecimal targetBalance) {
        assertRentIsCentExact(rent, PRINCIPAL, windows, dayCount, ANNUAL_RATE, targetBalance);
    }

    private static void assertRentIsCentExact(BigDecimal rent, BigDecimal targetBalance) {
        assertThat(roll(rent.subtract(HALF_CENT), MONTHLY_RATE, 12)
                .subtract(targetBalance)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(roll(rent.add(HALF_CENT), MONTHLY_RATE, 12)
                .subtract(targetBalance)).isLessThanOrEqualTo(BigDecimal.ZERO);
    }

    private static void assertRentIsCentExact(BigDecimal rent, BigDecimal principal,
            List<PeriodDate> windows, DayCountConvention dayCount, BigDecimal annualRate,
            BigDecimal targetBalance) {
        assertThat(PmtSolver.closingBalance(principal, windows, dayCount, annualRate,
                rent.subtract(HALF_CENT)).subtract(targetBalance))
                        .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(PmtSolver.closingBalance(principal, windows, dayCount, annualRate,
                rent.add(HALF_CENT)).subtract(targetBalance))
                        .isLessThanOrEqualTo(BigDecimal.ZERO);
    }

    /** Straight amortization at a fixed periodic rate, used to check what a rent really buys. */
    private static BigDecimal roll(BigDecimal rent, BigDecimal periodicRate, int periods) {
        BigDecimal balance = PRINCIPAL;
        for (int period = 0; period < periods; period++) {
            BigDecimal interest = balance.multiply(periodicRate);
            balance = balance.subtract(rent.subtract(interest));
        }
        return balance;
    }

    private static List<PeriodDate> monthlyWindows(String start, int count) {
        LocalDate first = LocalDate.parse(start);
        List<PeriodDate> windows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            LocalDate from = first.plusMonths(index);
            LocalDate to = first.plusMonths(index + 1);
            windows.add(PeriodDate.builder().startDate(from).endDate(to).paymentDate(to).build());
        }
        return windows;
    }
}
