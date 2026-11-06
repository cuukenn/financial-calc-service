package com.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincalc.common.error.BusinessException;
import com.fincalc.common.time.SystemClockProvider;
import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.CalculationOptions;
import com.fincalc.domain.model.CashFlow;
import com.fincalc.domain.model.EffectiveInterestRow;
import com.fincalc.domain.model.EffectiveInterestTable;
import com.fincalc.domain.model.GracePeriod;
import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PaymentFrequency;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.RateChangePolicy;
import com.fincalc.domain.model.RepaymentMethodId;
import com.fincalc.domain.model.ResidualHandling;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.model.Schedule;
import com.fincalc.domain.model.ScheduleHeader;
import com.fincalc.domain.support.algo.EqualInstallmentGenerator;
import com.fincalc.domain.support.cashflow.StandardCashFlowBuilder;
import com.fincalc.domain.support.dateroll.NoRoll;
import com.fincalc.domain.support.daycount.Thirty360;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * §9.7 effective-interest amortization table, measured against the same 1 000 000 @ 6 % flat
 * loan the metric tests pin (rents 86 066.43 × 11 plus the tail-adjusted 86 066.42, Σ interest
 * 32 797.15, raw periodIrr 0.0049999990613…).
 */
class EffectiveInterestCalculatorTest {

    private static final LocalDate T0 = LocalDate.of(2024, 1, 1);

    @Test
    void effective_interest_table_closes_to_zero() {
        Schedule s = schedule();
        BigDecimal periodIrr = IrrCalculator.periodIrr(flowsOf(s));
        EffectiveInterestTable t = EffectiveInterestCalculator.calculate(s, periodIrr);

        assertThat(t.rows()).hasSize(12);
        assertThat(t.rows().get(0).openingNetInvestment()).isEqualByComparingTo("1000000.00");
        assertThat(t.rows().get(11).closingNetInvestment()).isEqualByComparingTo("0.00");
        // 1 000 000.00 × 0.0049999990613… = 4999.99906… → 5 000.00 at scale 2.
        assertThat(t.rows().get(0).interestIncome()).isEqualByComparingTo("5000.00");
        assertThat(t.rows()).allSatisfy(row ->
                assertThat(row.principalRecovery()).isEqualByComparingTo(
                        row.cashReceived().subtract(row.interestIncome())));
        // The table carries the rate it was built with, unrounded.
        assertThat(t.eir()).isEqualByComparingTo(periodIrr);
        assertThat(t.rows()).allSatisfy(row -> {
            assertThat(row.openingNetInvestment().scale()).isEqualTo(2);
            assertThat(row.interestIncome().scale()).isEqualTo(2);
            assertThat(row.cashReceived().scale()).isEqualTo(2);
            assertThat(row.principalRecovery().scale()).isEqualTo(2);
            assertThat(row.closingNetInvestment().scale()).isEqualTo(2);
        });
    }

    /**
     * Σ interestIncome − Σ interestPaid closes at exactly 0.00 for a fee-free schedule: the
     * forced terminal balance pins Σ interestIncome to Σ rentDue − principal + target, and the
     * generator's own schedule conserves principal over the same rents (ledger invariant; the
     * brief only demanded {@code abs < 0.05}, the exact pin is the stronger truth).
     */
    @Test
    void reports_interest_difference_against_nominal_method() {
        Schedule s = schedule();
        EffectiveInterestTable t = EffectiveInterestCalculator.calculate(s,
                IrrCalculator.periodIrr(flowsOf(s)));

        assertThat(t.totalInterestDifference().abs()).isLessThan(new BigDecimal("0.05"));
        assertThat(t.totalInterestDifference()).isEqualByComparingTo("0.00");
        assertThat(t.totalInterestDifference().scale()).isEqualTo(2);
    }

    /**
     * Ledger ruling R50: §9.7's 「逐期利息差额」 is not a field of its own — it is the join of
     * {@code row.interestIncome()} with {@code installment.interestPaid()} on {@code periodNo}.
     * This test IS the reconciliation contract.
     */
    @Test
    void period_by_period_interest_difference_is_the_join_of_the_two_schedules() {
        Schedule s = schedule();
        EffectiveInterestTable t = EffectiveInterestCalculator.calculate(s,
                IrrCalculator.periodIrr(flowsOf(s)));
        List<Installment> installments = s.installments();

        BigDecimal joinedDifference = BigDecimal.ZERO;
        for (int index = 0; index < 12; index++) {
            EffectiveInterestRow row = t.rows().get(index);
            Installment installment = installments.get(index);
            assertThat(row.periodNo()).isEqualTo(installment.periodNo());
            BigDecimal periodDifference =
                    row.interestIncome().subtract(installment.interestPaid());
            // The effective and nominal schedules agree to the cent in every period: the raw
            // 0.0049999990613… rate prices the schedule's own rounded rents, so each period
            // differs by at most the cent the two roundings disagree on (fixture rows 9 and 12
            // are the ±0.01 pair).
            assertThat(periodDifference.abs()).as("period " + row.periodNo())
                    .isLessThanOrEqualTo(new BigDecimal("0.02"));
            joinedDifference = joinedDifference.add(periodDifference);
        }
        assertThat(joinedDifference).isEqualByComparingTo(t.totalInterestDifference());
    }

    /**
     * Ledger ruling R49: a 0 % lease is legitimate in this project (the generators already pass
     * their zero-rate tests), the table at EIR 0 is well-defined — interest income 0.00, every
     * rent is principal recovery — so {@code periodIrr == 0} is ACCEPTED, not rejected.
     */
    @Test
    void accepts_zero_period_irr_and_builds_a_flat_recovery_table() {
        LeaseElement element = element().toBuilder().nominalRate(BigDecimal.ZERO).build();
        Schedule s = generate(element);
        assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("83333.33");
        assertThat(s.installments().get(11).rentDue()).isEqualByComparingTo("83333.37");
        // The schedule's own flow set is consistent with EIR 0: its raw root is 0 to within
        // solver noise (Σ rents 1 000 000.00 = principal exactly).
        assertThat(IrrCalculator.periodIrr(flowsOf(s, element)).abs())
                .isLessThanOrEqualTo(new BigDecimal("1e-10"));

        EffectiveInterestTable t = EffectiveInterestCalculator.calculate(s, BigDecimal.ZERO);

        assertThat(t.rows()).hasSize(12);
        assertThat(t.rows()).allSatisfy(row -> {
            assertThat(row.interestIncome()).isEqualByComparingTo("0.00");
            assertThat(row.principalRecovery()).isEqualByComparingTo(row.cashReceived());
        });
        assertThat(t.rows().get(0).openingNetInvestment()).isEqualByComparingTo("1000000.00");
        assertThat(t.rows().get(0).cashReceived()).isEqualByComparingTo("83333.33");
        assertThat(t.rows().get(11).cashReceived()).isEqualByComparingTo("83333.37");
        assertThat(t.rows().get(11).closingNetInvestment()).isEqualByComparingTo("0.00");
        assertThat(t.totalInterestDifference()).isEqualByComparingTo("0.00");
    }

    /**
     * Ledger ruling R48/R49: only a NEGATIVE rate is rejected, with the new FCS-CALC-3007;
     * the brief's literal {@code ZERO} test moved to {@code -0.01} because 0 is accepted.
     */
    @Test
    void rejects_non_positive_rate() {
        assertThatThrownBy(() -> EffectiveInterestCalculator.calculate(schedule(),
                new BigDecimal("-0.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-CALC-3007");
    }

    /**
     * {@code periodIrr == null} is the solver's no-root marker (no sign change, no root in
     * [-0.9999, 10], budget exhausted). The engine task guards against it; this is the
     * defensive backstop with the defined spec code FCS-CALC-3003.
     */
    @Test
    void rejects_missing_period_irr_as_not_converged() {
        assertThatThrownBy(() -> EffectiveInterestCalculator.calculate(schedule(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-CALC-3003");
    }

    /**
     * Ledger ruling R51: a structural mismatch (rents that cannot amortize the principal — a
     * fee/deposit-vs-rent bookkeeping error shows up as thousands, not cents) is way outside
     * the cent-drift bound and must fail with §9.7's FCS-CALC-3006, not be absorbed.
     */
    @Test
    void rejects_a_table_that_cannot_close_within_tolerance() {
        Schedule broken = fabricatedSchedule("80000.00", "0.00");

        assertThatThrownBy(() -> EffectiveInterestCalculator.calculate(broken, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-CALC-3006");
    }

    /**
     * R51's accepting half: a 0.04 total sits inside the 0.01 × 12 bound — each period's
     * scale-2 interest rounding is at most half a cent — so it is absorbed into the last row's
     * interest income (downward here: the computed 0.04 closing overshoots the 0.00 target)
     * and the table still closes.
     */
    @Test
    void absorbs_a_cent_level_tail_into_the_last_rows_interest_income() {
        Schedule schedule = fabricatedSchedule("83333.33", "0.00");
        // Σ rentDue 999 999.96 against a schedule that claims to close at 0.00: the computed
        // last closing is 0.04, so the last row's interest income absorbs −0.04.
        EffectiveInterestTable table = EffectiveInterestCalculator.calculate(schedule,
                BigDecimal.ZERO);

        EffectiveInterestRow last = table.rows().get(11);
        assertThat(last.interestIncome()).isEqualByComparingTo("-0.04");
        assertThat(last.closingNetInvestment()).isEqualByComparingTo("0.00");
        assertThat(last.principalRecovery()).isEqualByComparingTo(
                last.cashReceived().subtract(last.interestIncome()));
        assertThat(table.totalInterestDifference()).isEqualByComparingTo("-0.04");
    }

    /**
     * Ledger ruling R47: the terminal target is the schedule's own last closing balance, not
     * literal zero. A schedule whose rent stream settles down to an outstanding 100 000.00
     * closes its table at 100 000.00 — the target rule is what keeps the outstanding balance
     * out of 末期利息 (last-row interest income stays 0.00, it is not swallowed).
     */
    @Test
    void terminal_target_is_the_schedules_own_balance_not_zero() {
        Schedule schedule = fabricatedSchedule("75000.00", "100000.00");
        // Σ rentDue 900 000.00 against an opening of 1 000 000.00 that stops at 100 000.00.
        EffectiveInterestTable t = EffectiveInterestCalculator.calculate(schedule,
                BigDecimal.ZERO);

        assertThat(t.rows().get(11).closingNetInvestment()).isEqualByComparingTo("100000.00");
        assertThat(t.rows().get(11).closingNetInvestment()).isNotZero();
        assertThat(t.rows().get(11).interestIncome()).isEqualByComparingTo("0.00");
        assertThat(t.totalInterestDifference()).isEqualByComparingTo("0.00");
    }

    /**
     * The honest limit of R47, pinned with the real event-table rate: cashReceived never
     * carries residual money, so pricing a BALLOON schedule with the §9.4 event table's IRR
     * (whose RESIDUAL leg IrrCalculator discounts one sequence past the last rent, raw root
     * 0.00493324…) makes the chain land a residual-step short of the schedule's stop point —
     * 99 509.10 against the exact 100 000.00 / (1 + eir) = 99 509.13, i.e. a 490.90
     * structural gap, thousands-of-fractions rather than the cent drift R51 absorbs. The
     * calculator refuses to paper over it with FCS-CALC-3006 instead of fabricating 490.90 of
     * 末期利息; feeding a rate consistent with the cash stream is the engine task's call.
     */
    @Test
    void residual_priced_by_the_rate_but_never_received_as_cash_is_a_structural_mismatch() {
        LeaseElement element = element().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();
        Schedule s = generate(element);
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("100000.00");
        assertThat(IrrCalculator.periodIrr(flowsOf(s, element)))
                .isLessThan(new BigDecimal("0.005"));

        assertThatThrownBy(() -> EffectiveInterestCalculator.calculate(s,
                IrrCalculator.periodIrr(flowsOf(s, element))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-CALC-3006")
                .hasMessageContaining("100000.00");
    }

    /** §9.7 tables are returned immutable: rows cannot be replaced or appended. */
    @Test
    void returned_table_is_immutable() {
        EffectiveInterestTable t = EffectiveInterestCalculator.calculate(schedule(),
                IrrCalculator.periodIrr(flowsOf(schedule())));

        assertThatThrownBy(() -> t.rows().add(null)).isInstanceOf(Exception.class);
    }

    private static Schedule schedule() {
        return generate(element());
    }

    private static Schedule generate(LeaseElement element) {
        return new EqualInstallmentGenerator().generate(new CalculationContext(element, null,
                new Thirty360(), new NoRoll(), SystemClockProvider.INSTANCE,
                CalculationOptions.defaults()));
    }

    private static List<CashFlow> flowsOf(Schedule schedule) {
        return flowsOf(schedule, element());
    }

    private static List<CashFlow> flowsOf(Schedule schedule, LeaseElement element) {
        return new StandardCashFlowBuilder().build(schedule, element,
                CalculationOptions.defaults());
    }

    /**
     * A hand-built 12-period schedule against an opening principal of 1 000 000.00: every rent
     * is {@code rent}, every period carries zero paid interest (so {@code principalDue = rent}
     * until the last period, which lands the balance on {@code finalClosing}). The calculator
     * reads only the first opening balance, the rent legs and the last closing balance from it,
     * which is exactly the input a structurally inconsistent input pair would provide.
     */
    private static Schedule fabricatedSchedule(String rent, String finalClosing) {
        List<Installment> installments = new ArrayList<>();
        BigDecimal balance = new BigDecimal("1000000.00");
        BigDecimal rentDue = new BigDecimal(rent);
        BigDecimal target = new BigDecimal(finalClosing);
        for (int periodNo = 1; periodNo <= 12; periodNo++) {
            BigDecimal principalDue = periodNo == 12
                    ? balance.subtract(target) : rentDue;
            BigDecimal closing = balance.subtract(principalDue);
            installments.add(new Installment(periodNo, T0.plusMonths(periodNo - 1),
                    T0.plusMonths(periodNo), 30, balance, principalDue, new BigDecimal("0.00"),
                    new BigDecimal("0.00"), new BigDecimal("0.00"), null, rentDue, closing,
                    new BigDecimal("1000000.00").subtract(closing), new BigDecimal("0.00"),
                    BigDecimal.ZERO, "TEST"));
            balance = closing;
        }
        ScheduleHeader header = new ScheduleHeader(null, 12, T0, T0.plusMonths(12), "TEST",
                new BigDecimal("0.00"));
        return new Schedule(header, installments, Collections.emptyList(),
                Collections.emptyList());
    }

    private static LeaseElement element() {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000"))
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(T0)
                .firstRentDate(LocalDate.of(2024, 2, 1))
                .periods(12)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualValue(BigDecimal.ZERO)
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(BigDecimal.ZERO)
                .vatRate(new BigDecimal("0.06"))
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
