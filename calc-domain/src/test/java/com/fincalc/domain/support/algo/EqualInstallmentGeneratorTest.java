package com.fincalc.domain.support.algo;

import static org.assertj.core.api.Assertions.assertThat;

import static java.util.stream.Collectors.toList;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.fincalc.common.money.MoneyUtils;
import com.fincalc.common.time.SystemClockProvider;
import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.CalculationOptions;
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
import com.fincalc.domain.support.dateroll.NoRoll;
import com.fincalc.domain.support.daycount.Thirty360;

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
