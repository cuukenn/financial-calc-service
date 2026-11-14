package io.github.cuukenn.fincalc.domain.support.algo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

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

class EqualPrincipalGeneratorTest {

    @Test
    void golden_schedule() {
        Schedule s = generate(new BigDecimal("1000000"), new BigDecimal("0.06"), 12);

        assertThat(s.installments().get(0).principalDue()).isEqualByComparingTo("83333.33");
        assertThat(s.installments().get(0).interestAccrued()).isEqualByComparingTo("5000.00");
        assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("88333.33");
        assertThat(s.installments().get(1).openingBalance()).isEqualByComparingTo("916666.67");
        assertThat(s.installments().get(1).interestAccrued()).isEqualByComparingTo("4583.33");
        assertThat(s.installments().get(11).principalDue()).isEqualByComparingTo("83333.37");
    }

    @Test
    void interest_decreases_monotonically() {
        Schedule s = generate(new BigDecimal("1000000"), new BigDecimal("0.06"), 12);

        for (int i = 1; i < 12; i++) {
            assertThat(s.installments().get(i).interestAccrued())
                    .isLessThan(s.installments().get(i - 1).interestAccrued());
        }
    }

    @Test
    void closes_to_zero_and_conserves_principal() {
        Schedule s = generate(new BigDecimal("1000000"), new BigDecimal("0.06"), 12);

        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void zero_rate_keeps_rent_constant() {
        Schedule s = generate(new BigDecimal("1200000"), BigDecimal.ZERO, 12);

        assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("100000.00");
        assertThat(s.installments().get(11).rentDue()).isEqualByComparingTo("100000.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void balloon_repays_principal_minus_residual() {
        LeaseElement element = baseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12)
                .toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();

        Schedule s = new EqualPrincipalGenerator().generate(context(element));

        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("100000.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void rejects_advance_timing() {
        LeaseElement element = baseElement(new BigDecimal("1000000"), new BigDecimal("0.06"), 12)
                .toBuilder()
                .timing(PaymentTiming.IN_ADVANCE)
                .build();

        assertThat(new EqualPrincipalGenerator().supports(context(element))).isFalse();
        assertThatThrownBy(() -> new EqualPrincipalGenerator().generate(context(element)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-2004");
    }

    @Test
    void exposes_code_and_order() {
        EqualPrincipalGenerator generator = new EqualPrincipalGenerator();
        assertThat(generator.code()).isEqualTo("EQUAL_PRINCIPAL");
        assertThat(generator.order()).isEqualTo(20);
    }

    private static Schedule generate(BigDecimal principal, BigDecimal rate, int periods) {
        return new EqualPrincipalGenerator().generate(context(baseElement(principal, rate, periods)));
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
                .method(RepaymentMethodId.EQUAL_PRINCIPAL)
                .gracePeriod(GracePeriod.none())
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
