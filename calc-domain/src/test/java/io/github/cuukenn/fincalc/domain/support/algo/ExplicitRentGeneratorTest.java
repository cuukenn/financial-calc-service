package io.github.cuukenn.fincalc.domain.support.algo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.support.dateroll.NoRoll;
import io.github.cuukenn.fincalc.domain.support.daycount.Thirty360;

/**
 * Unit tests of the R69 explicit-rent split: each period's rent is taken verbatim from
 * {@code LeaseElement.explicitRents}, the interest accrues on the opening balance at the
 * period rate (THIRTY_360 monthly here, so annual/12) and the principal is the remainder —
 * with no last-period clamp. A rent that cannot even cover the accrued interest fails loud
 * (R17 backstop, FCS-DOM-2003) and a rent sequence that does not amortize the balance to the
 * residual is flagged by the conservation validators, never absorbed.
 */
class ExplicitRentGeneratorTest {

    @Test
    void splits_each_rent_into_interest_and_principal() {
        // Hand chain (THIRTY_360 monthly, 6% -> 0.005 per period, principal 90 000):
        //   P1: interest 90 000.00 x 0.005 = 450.00; rent 30 200.00 -> principal 29 750.00,
        //       closing 60 250.00
        //   P2: interest 60 250.00 x 0.005 = 301.25; rent 30 200.00 -> principal 29 898.75,
        //       closing 30 351.25
        //   P3: interest 30 351.25 x 0.005 = 151.75625 -> accrued rounds to 151.76; rent
        //       30 503.01 -> principal 30 351.25, closing 0.00 (exact amortization)
        Schedule s = new ExplicitRentGenerator().generate(context(baseElement(
                "90000", "0.06", 3, List.of("30200.00", "30200.00", "30503.01"))));

        assertThat(s.installments()).hasSize(3);
        assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("30200.00");
        assertThat(s.installments().get(0).interestAccrued()).isEqualByComparingTo("450.00");
        assertThat(s.installments().get(0).principalDue()).isEqualByComparingTo("29750.00");
        assertThat(s.installments().get(0).closingBalance()).isEqualByComparingTo("60250.00");
        assertThat(s.installments().get(1).interestAccrued()).isEqualByComparingTo("301.25");
        assertThat(s.installments().get(1).principalDue()).isEqualByComparingTo("29898.75");
        assertThat(s.installments().get(1).closingBalance()).isEqualByComparingTo("30351.25");
        assertThat(s.installments().get(2).rentDue()).isEqualByComparingTo("30503.01");
        assertThat(s.installments().get(2).interestAccrued()).isEqualByComparingTo("151.76");
        assertThat(s.installments().get(2).principalDue()).isEqualByComparingTo("30351.25");
        assertThat(s.installments().get(2).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(2).cumulativeInterest()).isEqualByComparingTo("903.01");
        assertThat(s.hasErrors()).isFalse();
        assertThat(s.header().roundingDifference()).isEqualByComparingTo("0.00");
    }

    @Test
    void zero_rate_pays_the_rent_as_principal_exactly() {
        // Zero nominal rate: no interest accrues, so principal = rent in every period and
        // 12 x 100 000.00 exactly amortizes 1 200 000.00.
        List<String> rents = Arrays.asList(
                "100000.00", "100000.00", "100000.00", "100000.00", "100000.00", "100000.00",
                "100000.00", "100000.00", "100000.00", "100000.00", "100000.00", "100000.00");
        Schedule s = new ExplicitRentGenerator().generate(context(baseElement(
                "1200000", "0", 12, rents)));

        assertThat(s.installments()).allSatisfy(i -> {
            assertThat(i.interestAccrued()).isEqualByComparingTo("0.00");
            assertThat(i.principalDue()).isEqualByComparingTo("100000.00");
            assertThat(i.rentDue()).isEqualByComparingTo("100000.00");
        });
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void rent_below_accrued_interest_fails_loud_with_negative_principal() {
        // R17 backstop: the 100.00 rent cannot cover the 500.00 of accrued interest, so the
        // split would need principal -400.00 — rejected, never absorbed.
        LeaseElement element = baseElement("100000", "0.06", 2, List.of("100.00", "200000.00"));
        assertThatThrownBy(() -> new ExplicitRentGenerator().generate(context(element)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-2003")
                .hasMessageContaining("-400.00");
    }

    @Test
    void schedule_that_does_not_amortize_is_flagged_not_clamped() {
        // R69: rents are given, not derived. Overpaying leaves a negative tail balance; the
        // schedule is kept and the conservation validators flag it (3001 + 3002) instead of the
        // generator silently adjusting the rents.
        Schedule s = new ExplicitRentGenerator().generate(context(baseElement(
                "100000", "0.06", 2, List.of("50500.00", "60250.00"))));

        assertThat(s.installments()).hasSize(2);
        assertThat(s.installments().get(1).closingBalance()).isEqualByComparingTo("-10000.00");
        assertThat(s.hasErrors()).isTrue();
        assertThat(s.issues()).extracting(i -> i.code())
                .contains("FCS-CALC-3001", "FCS-CALC-3002");
    }

    @Test
    void exposes_code_and_order() {
        ExplicitRentGenerator generator = new ExplicitRentGenerator();
        assertThat(generator.code()).isEqualTo("EXPLICIT_RENT");
        assertThat(generator.order()).isEqualTo(50);
    }

    @Test
    void supports_only_explicit_rent_in_arrears() {
        ExplicitRentGenerator generator = new ExplicitRentGenerator();
        assertThat(generator.supports(context(baseElement("1000", "0.06", 3,
                List.of("400.00", "400.00", "400.00"))))).isTrue();

        LeaseElement otherMethod = baseElement("1000", "0.06", 3,
                List.of("400.00", "400.00", "400.00")).toBuilder()
                .method(RepaymentMethodId.EQUAL_PRINCIPAL)
                .explicitRents(null)
                .build();
        assertThat(generator.supports(context(otherMethod))).isFalse();

        LeaseElement advance = baseElement("1000", "0.06", 3,
                List.of("400.00", "400.00", "400.00")).toBuilder()
                .timing(PaymentTiming.IN_ADVANCE)
                .build();
        assertThat(generator.supports(context(advance))).isFalse();
    }

    @Test
    void does_not_support_advance_timing() {
        LeaseElement advance = baseElement("90000", "0.06", 3,
                List.of("30200.00", "30200.00", "30503.01")).toBuilder()
                .timing(PaymentTiming.IN_ADVANCE)
                .build();
        assertThatThrownBy(() -> new ExplicitRentGenerator().generate(context(advance)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-2004");
    }

    @Test
    void non_explicit_method_cannot_carry_rents() {
        // Task-1 loud construction rule (FCS-DOM-1008): a non-EXPLICIT_RENT method carrying
        // explicitRents never becomes an element, so the generator never sees one.
        assertThatThrownBy(() -> baseElement("90000", "0.06", 3,
                List.of("30200.00", "30200.00", "30503.01")).toBuilder()
                .method(RepaymentMethodId.EQUAL_PRINCIPAL)
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1008");
    }

    private static CalculationContext context(LeaseElement element) {
        return new CalculationContext(element, null, new Thirty360(), new NoRoll(),
                SystemClockProvider.INSTANCE, CalculationOptions.defaults());
    }

    private static LeaseElement baseElement(String principal, String rate, int periods,
            List<String> rents) {
        List<BigDecimal> parsed = new java.util.ArrayList<>();
        for (String rent : rents) {
            parsed.add(new BigDecimal(rent));
        }
        return LeaseElement.builder()
                .principal(new BigDecimal(principal))
                .nominalRate(new BigDecimal(rate))
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
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .explicitRents(parsed)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
