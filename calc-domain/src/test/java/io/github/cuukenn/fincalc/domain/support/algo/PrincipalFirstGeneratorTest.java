package io.github.cuukenn.fincalc.domain.support.algo;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.github.cuukenn.fincalc.domain.support.daycount.Thirty360;

class PrincipalFirstGeneratorTest {

    @Test
    void defers_all_interest_to_maturity() {
        Schedule s = generate("1000000", "0.06", 12);
        for (int i = 0; i < 11; i++) {
            assertThat(s.installments().get(i).principalDue()).isEqualByComparingTo("83333.33");
            assertThat(s.installments().get(i).interestPaid()).isEqualByComparingTo("0.00");
            assertThat(s.installments().get(i).rentDue()).isEqualByComparingTo("83333.33");
        }
        Installment last = s.installments().get(11);
        assertThat(last.interestPaid()).isGreaterThan(new BigDecimal("30000"));
        assertThat(last.rentDue()).isEqualByComparingTo(last.principalDue().add(last.interestPaid()));
        assertThat(last.closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void total_interest_equals_sum_of_accrued() {
        Schedule s = generate("1000000", "0.06", 12);
        BigDecimal accrued = MoneyUtils.sum(
                s.installments().stream().map(Installment::interestAccrued).collect(toList()));
        BigDecimal paid = MoneyUtils.sum(
                s.installments().stream().map(Installment::interestPaid).collect(toList()));
        assertThat(paid).isEqualByComparingTo(accrued);
    }

    @Test
    void maturity_rent_carries_every_period_of_accrued_interest() {
        Schedule s = generate("1000000", "0.06", 12);
        Installment last = s.installments().get(11);
        assertThat(last.principalDue()).isEqualByComparingTo("83333.37");
        assertThat(last.interestAccrued()).isEqualByComparingTo("416.67");
        assertThat(last.interestPaid()).isEqualByComparingTo("32500.00");
        assertThat(last.rentDue()).isEqualByComparingTo("115833.37");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void zero_rate_defers_nothing() {
        Schedule s = generate("1000000", "0", 3);
        assertThat(s.installments().get(2).interestPaid()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(2).rentDue()).isEqualByComparingTo("333333.34");
        assertThat(s.installments().get(2).closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void balloon_residual_stays_outstanding() {
        LeaseElement element = baseElement("1000000", "0.06", 12).toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();
        Schedule s = new PrincipalFirstGenerator().generate(context(element));
        assertThat(s.installments().get(0).principalDue()).isEqualByComparingTo("75000.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("100000.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void does_not_support_advance_timing() {
        LeaseElement advance = baseElement("1000000", "0.06", 12).toBuilder()
                .timing(PaymentTiming.IN_ADVANCE)
                .build();
        assertThatThrownBy(() -> new PrincipalFirstGenerator().generate(context(advance)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-2004");
    }

    @Test
    void exposes_code_and_order() {
        PrincipalFirstGenerator generator = new PrincipalFirstGenerator();
        assertThat(generator.code()).isEqualTo("PRINCIPAL_FIRST");
        assertThat(generator.order()).isEqualTo(40);
    }

    private static Schedule generate(String principal, String rate, int periods) {
        return new PrincipalFirstGenerator()
                .generate(context(baseElement(principal, rate, periods)));
    }

    private static CalculationContext context(LeaseElement element) {
        return new CalculationContext(element, null, new Thirty360(), new NoRoll(),
                SystemClockProvider.INSTANCE, CalculationOptions.defaults());
    }

    private static LeaseElement baseElement(String principal, String rate, int periods) {
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
                .method(RepaymentMethodId.PRINCIPAL_FIRST)
                .gracePeriod(GracePeriod.none())
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
