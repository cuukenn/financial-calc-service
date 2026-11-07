package io.github.cuukenn.fincalc.domain.support.algo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.util.stream.Collectors.toList;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.InterestTreatmentKind;
import io.github.cuukenn.fincalc.domain.model.PrincipalPlanKind;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.RuleOutcome;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.SpecialActionKind;
import io.github.cuukenn.fincalc.domain.port.RuleSetView;
import io.github.cuukenn.fincalc.domain.support.dateroll.NoRoll;
import io.github.cuukenn.fincalc.domain.support.daycount.Act365F;
import io.github.cuukenn.fincalc.domain.support.daycount.Thirty360;

class EqualInstallmentGeneratorTest {

    @Test
    void golden_first_three_periods() {
        Schedule s = generate(new BigDecimal("1000000"), new BigDecimal("0.06"), 12);

        assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("86066.43");
        assertThat(s.installments().get(0).interestAccrued()).isEqualByComparingTo("5000.00");
        assertThat(s.installments().get(0).principalDue()).isEqualByComparingTo("81066.43");
        assertThat(s.installments().get(0).closingBalance()).isEqualByComparingTo("918933.57");
        assertThat(s.installments().get(1).interestAccrued()).isEqualByComparingTo("4594.67");
        assertThat(s.installments().get(1).principalDue()).isEqualByComparingTo("81471.76");
        assertThat(s.installments().get(1).closingBalance()).isEqualByComparingTo("837461.81");
    }

    @Test
    void last_period_closes_to_zero_and_principal_is_conserved() {
        Schedule s = generate(new BigDecimal("1000000"), new BigDecimal("0.06"), 12);

        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(MoneyUtils.sum(s.installments().stream().map(Installment::principalDue)
                .collect(toList()))).isEqualByComparingTo("1000000.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void zero_rate_divides_principal_evenly() {
        Schedule s = generate(new BigDecimal("1200000"), BigDecimal.ZERO, 12);

        assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("100000.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void single_period_repays_principal_plus_one_period_interest() {
        Schedule s = generate(new BigDecimal("1000000"), new BigDecimal("0.06"), 1);

        assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("1005000.00");
        assertThat(s.installments().get(0).closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void every_non_final_rent_is_equal() {
        Schedule s = generate(new BigDecimal("1000000"), new BigDecimal("0.06"), 12);

        for (int i = 0; i < 11; i++) {
            assertThat(s.installments().get(i).rentDue()).isEqualByComparingTo("86066.43");
        }
    }

    @Test
    void golden_schedule_records_the_closed_form_solver() {
        Schedule s = generate(new BigDecimal("1000000"), new BigDecimal("0.06"), 12);

        assertThat(s.header().solver()).isEqualTo("CLOSED_FORM");
    }

    @Test
    void solves_act_windows_iteratively_and_closes_to_zero() {
        LeaseElement element = baseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12)
                .toBuilder()
                .dayCountCode("ACT_365F")
                .build();
        CalculationContext actContext = new CalculationContext(element, null, new Act365F(),
                new NoRoll(), SystemClockProvider.INSTANCE, CalculationOptions.defaults());

        Schedule s = new EqualInstallmentGenerator().generate(actContext);

        assertThat(s.header().solver()).isEqualTo("ITERATIVE");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(MoneyUtils.sum(s.installments().stream().map(Installment::principalDue)
                .collect(toList()))).isEqualByComparingTo("1000000.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void rejects_non_uniform_period_rates_instead_of_emitting_negative_balances() {
        LeaseElement element = baseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12)
                .toBuilder()
                .firstRentDate(LocalDate.of(2024, 7, 1))
                .build();

        assertThatThrownBy(() -> new EqualInstallmentGenerator().generate(context(element)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-2002");
    }

    @Test
    void does_not_support_advance_timing() {
        LeaseElement element = baseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12)
                .toBuilder()
                .timing(PaymentTiming.IN_ADVANCE)
                .build();

        assertThat(new EqualInstallmentGenerator().supports(context(element))).isFalse();
        assertThatThrownBy(() -> new EqualInstallmentGenerator().generate(context(element)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-2004");
    }

    @Test
    void exposes_code_and_order() {
        EqualInstallmentGenerator generator = new EqualInstallmentGenerator();
        assertThat(generator.code()).isEqualTo("EQUAL_INSTALLMENT");
        assertThat(generator.order()).isEqualTo(10);
    }

    @Test
    void balloon_leaves_residual_outstanding_and_conserves_repaid_principal() {
        LeaseElement element = baseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12)
                .toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();

        Schedule s = new EqualInstallmentGenerator().generate(context(element));

        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("100000.00");
        assertThat(MoneyUtils.sum(s.installments().stream().map(Installment::principalDue)
                .collect(toList()))).isEqualByComparingTo("900000.00");
        assertThat(s.hasErrors()).isFalse();
    }

    // ---- R71 segmented three-state tests (rule set present; hand-computed golden values) ----

    @Test
    void segmented_resolve_re_prices_each_rate_segment_hand_computed() {
        // Hand chain: i1 = 0.03 x 30/360 = 0.0025, i2 = 0.06 x 30/360 = 0.005.
        // Segment 1 PMT = 1 000 000 x i1 / (1 - (1+i1)^-12) = 84 693.70 over periods 1-6.
        // Segment 2 opens at B7 = 503 745.25 and re-solves over the 6 remaining periods:
        // PMT = 503 745.25 x i2 / (1 - (1+i2)^-6) = 85 432.91; period 12 is the last-period clamp.
        Schedule s = segmented(RateChangePolicy.RESOLVE, twoSegmentRules());

        assertThat(s.header().solver()).isEqualTo("SEGMENTED_RESOLVE");
        assertThat(s.hasErrors()).isFalse();
        assertRow(s, 0, "1000000.00", "82193.70", "2500.00", "84693.70", "917806.30");
        assertRow(s, 1, "917806.30", "82399.18", "2294.52", "84693.70", "835407.12");
        assertRow(s, 2, "835407.12", "82605.18", "2088.52", "84693.70", "752801.94");
        assertRow(s, 3, "752801.94", "82811.70", "1882.00", "84693.70", "669990.24");
        assertRow(s, 4, "669990.24", "83018.72", "1674.98", "84693.70", "586971.52");
        assertRow(s, 5, "586971.52", "83226.27", "1467.43", "84693.70", "503745.25");
        assertRow(s, 6, "503745.25", "82914.18", "2518.73", "85432.91", "420831.07");
        assertRow(s, 7, "420831.07", "83328.75", "2104.16", "85432.91", "337502.32");
        assertRow(s, 8, "337502.32", "83745.40", "1687.51", "85432.91", "253756.92");
        assertRow(s, 9, "253756.92", "84164.13", "1268.78", "85432.91", "169592.79");
        assertRow(s, 10, "169592.79", "84584.95", "847.96", "85432.91", "85007.84");
        assertRow(s, 11, "85007.84", "85007.84", "425.04", "85432.88", "0.00");
    }

    @Test
    void segmented_fixed_rent_keeps_the_initial_pmt_and_changes_only_the_split() {
        // Hand chain: PMT solved once from [1 000 000 + 12 periods + 3%] = 84 693.70 and kept for
        // the whole term; the 6% segment only raises the interest share. The last period is the
        // clamp, which absorbs the accumulated drift (rent jumps to 89 184.75 and closes to zero).
        Schedule s = segmented(RateChangePolicy.FIXED_RENT, twoSegmentRules());

        assertThat(s.header().solver()).isEqualTo("FIXED_RENT");
        assertThat(s.hasErrors()).isFalse();
        assertRow(s, 0, "1000000.00", "82193.70", "2500.00", "84693.70", "917806.30");
        assertRow(s, 5, "586971.52", "83226.27", "1467.43", "84693.70", "503745.25");
        assertRow(s, 6, "503745.25", "82174.97", "2518.73", "84693.70", "421570.28");
        assertRow(s, 7, "421570.28", "82585.85", "2107.85", "84693.70", "338984.43");
        assertRow(s, 8, "338984.43", "82998.78", "1694.92", "84693.70", "255985.65");
        assertRow(s, 9, "255985.65", "83413.77", "1279.93", "84693.70", "172571.88");
        assertRow(s, 10, "172571.88", "83830.84", "862.86", "84693.70", "88741.04");
        assertRow(s, 11, "88741.04", "88741.04", "443.71", "89184.75", "0.00");
    }

    @Test
    void segmented_drift_re_prices_every_period_hand_computed() {
        // Hand chain: rent(p) = B(p) x i(p) / (1 - (1+i(p))^{-(13-p)}), re-solved every period
        // from the current balance. From period 8 on the drift rents fall a cent below the
        // RESOLVE rents and the balance closes to zero with no clamp distortion.
        Schedule s = segmented(RateChangePolicy.DRIFT, twoSegmentRules());

        assertThat(s.header().solver()).isEqualTo("DRIFT");
        assertThat(s.hasErrors()).isFalse();
        assertRow(s, 0, "1000000.00", "82193.70", "2500.00", "84693.70", "917806.30");
        assertRow(s, 5, "586971.52", "83226.27", "1467.43", "84693.70", "503745.25");
        assertRow(s, 6, "503745.25", "82914.18", "2518.73", "85432.91", "420831.07");
        assertRow(s, 7, "420831.07", "83328.74", "2104.16", "85432.90", "337502.33");
        assertRow(s, 8, "337502.33", "83745.40", "1687.51", "85432.91", "253756.93");
        assertRow(s, 9, "253756.93", "84164.13", "1268.78", "85432.91", "169592.80");
        assertRow(s, 10, "169592.80", "84584.94", "847.96", "85432.90", "85007.86");
        assertRow(s, 11, "85007.86", "85007.86", "425.04", "85432.90", "0.00");
    }

    @Test
    void single_segment_rule_set_matches_the_no_rule_set_schedule() {
        // One uniform rule rate = a single segment at that rate; the rents must be identical to
        // the no-rule-set closed form run at the same nominal rate.
        LeaseElement element = baseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12);
        RuleSetView singleSegment = rateRules(12, "0.06", "0.06");

        Schedule withRules = new EqualInstallmentGenerator()
                .generate(context(element, singleSegment));
        Schedule withoutRules = new EqualInstallmentGenerator().generate(context(element, null));

        assertThat(withRules.installments()).hasSameSizeAs(withoutRules.installments());
        for (int i = 0; i < withoutRules.installments().size(); i++) {
            assertThat(withRules.installments().get(i).rentDue())
                    .isEqualByComparingTo(withoutRules.installments().get(i).rentDue());
            assertThat(withRules.installments().get(i).principalDue())
                    .isEqualByComparingTo(withoutRules.installments().get(i).principalDue());
        }
        assertThat(withRules.hasErrors()).isFalse();
    }

    @Test
    void fixed_rent_equals_the_uniform_nominal_payment() {
        // FIXED_RENT prices once from the initial (3%) segment rate, so its rent must equal the
        // PMT of a no-rule-set schedule at that same uniform nominal rate: 84 693.70. The rates
        // then only alter the interest/principal split, not the rent.
        Schedule fixed = segmented(RateChangePolicy.FIXED_RENT, twoSegmentRules());
        LeaseElement uniform = baseElement(new BigDecimal("1000000"), new BigDecimal("0.03"), 12);
        Schedule uniformNominal = new EqualInstallmentGenerator().generate(context(uniform, null));

        for (int i = 0; i < 11; i++) {
            assertThat(fixed.installments().get(i).rentDue())
                    .isEqualByComparingTo(uniformNominal.installments().get(i).rentDue());
            assertThat(fixed.installments().get(i).rentDue()).isEqualByComparingTo("84693.70");
        }
    }

    @Test
    void drift_and_resolve_diverge_after_the_segment_boundary() {
        // At the boundary itself (period 7) both modes solve from the same balance over the same
        // 6 remaining periods at the same rate, so their rents coincide there by construction;
        // from period 8 on DRIFT re-prices while RESOLVE holds the segment rent.
        Schedule resolve = segmented(RateChangePolicy.RESOLVE, twoSegmentRules());
        Schedule drift = segmented(RateChangePolicy.DRIFT, twoSegmentRules());

        assertThat(drift.installments().get(6).rentDue())
                .isEqualByComparingTo(resolve.installments().get(6).rentDue());
        assertThat(drift.installments().get(7).rentDue())
                .isNotEqualByComparingTo(resolve.installments().get(7).rentDue());
        assertThat(drift.installments().get(11).rentDue())
                .isNotEqualByComparingTo(resolve.installments().get(11).rentDue());
    }

    @Test
    void zero_rate_in_a_later_segment_divides_the_remaining_balance() {
        // Segment 2 at 0%: no division by zero; the segment rent is the remaining balance spread
        // over the 6 remaining periods (503 745.25 / 6 = 83 957.54) and the clamp closes exactly.
        Schedule s = segmented(RateChangePolicy.RESOLVE, rateRules(6, "0.03", "0"));

        assertThat(s.hasErrors()).isFalse();
        assertThat(s.installments().get(6).rentDue()).isEqualByComparingTo("83957.54");
        assertThat(s.installments().get(6).interestAccrued()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(11).interestAccrued()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(11).rentDue()).isEqualByComparingTo("83957.55");
    }

    private static RuleSetView twoSegmentRules() {
        return rateRules(6, "0.03", "0.06");
    }

    /** Unconditional rate rules: periods 1..splitAfter at {@code firstRate}, the rest at the other. */
    private static RuleSetView rateRules(int splitAfter, String firstRate, String secondRate) {
        return (periodNo, openingBalance, days, defaultAnnualRate) -> {
            boolean first = periodNo <= splitAfter;
            return new RuleOutcome(true, first ? "R1" : "R2",
                    new BigDecimal(first ? firstRate : secondRate), null,
                    PrincipalPlanKind.INHERIT, null, null,
                    InterestTreatmentKind.PAY_AS_INCURRED, SpecialActionKind.NONE, null);
        };
    }

    private static Schedule segmented(RateChangePolicy policy, RuleSetView ruleSet) {
        LeaseElement element = baseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12)
                .toBuilder()
                .rateChangePolicy(policy)
                .build();
        return new EqualInstallmentGenerator().generate(context(element, ruleSet));
    }

    private static CalculationContext context(LeaseElement element, RuleSetView ruleSet) {
        return new CalculationContext(element, ruleSet, new Thirty360(), new NoRoll(),
                SystemClockProvider.INSTANCE, CalculationOptions.defaults());
    }

    private static void assertRow(Schedule s, int index, String opening, String principal,
            String interest, String rent, String closing) {
        Installment i = s.installments().get(index);
        assertThat(i.openingBalance()).isEqualByComparingTo(opening);
        assertThat(i.principalDue()).isEqualByComparingTo(principal);
        assertThat(i.interestAccrued()).isEqualByComparingTo(interest);
        assertThat(i.rentDue()).isEqualByComparingTo(rent);
        assertThat(i.closingBalance()).isEqualByComparingTo(closing);
    }

    private static Schedule generate(BigDecimal principal, BigDecimal rate, int periods) {
        return new EqualInstallmentGenerator().generate(context(baseElement(principal, rate, periods)));
    }

    private static CalculationContext context(LeaseElement element) {
        return new CalculationContext(element, null, new Thirty360(), new NoRoll(),
                SystemClockProvider.INSTANCE, CalculationOptions.defaults());
    }

    private static LeaseElement baseElement(BigDecimal principal, BigDecimal rate, int periods) {
        return LeaseElement.builder()
                .principal(principal)
                .nominalRate(rate)
                .commenceDate(LocalDate.of(2024, 1, 1))
                .firstRentDate(LocalDate.of(2024, 2, 1))
                .periods(periods)
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
