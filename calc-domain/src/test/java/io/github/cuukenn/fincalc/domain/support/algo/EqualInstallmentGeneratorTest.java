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
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.Schedule;
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
