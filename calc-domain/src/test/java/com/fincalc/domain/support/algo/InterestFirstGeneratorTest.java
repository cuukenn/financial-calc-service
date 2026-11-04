package com.fincalc.domain.support.algo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.fincalc.common.error.BusinessException;
import com.fincalc.common.time.SystemClockProvider;
import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.CalculationOptions;
import com.fincalc.domain.model.GracePeriod;
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

class InterestFirstGeneratorTest {

    @Test
    void pays_only_interest_until_maturity() {
        Schedule s = generate("1000000", "0.06", 12);
        for (int i = 0; i < 11; i++) {
            assertThat(s.installments().get(i).principalDue()).isEqualByComparingTo("0.00");
            assertThat(s.installments().get(i).interestAccrued()).isEqualByComparingTo("5000.00");
            assertThat(s.installments().get(i).interestPaid()).isEqualByComparingTo("5000.00");
            assertThat(s.installments().get(i).rentDue()).isEqualByComparingTo("5000.00");
            assertThat(s.installments().get(i).closingBalance()).isEqualByComparingTo("1000000.00");
        }
        assertThat(s.installments().get(11).principalDue()).isEqualByComparingTo("1000000.00");
        assertThat(s.installments().get(11).rentDue()).isEqualByComparingTo("1005000.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void schedule_closes_and_reports_no_conservation_issue() {
        Schedule s = generate("1000000", "0.06", 12);
        assertThat(s.header().roundingDifference()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void zero_rate_only_repay_principal_at_maturity() {
        Schedule s = generate("1000000", "0", 3);
        assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(1).rentDue()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(2).rentDue()).isEqualByComparingTo("1000000.00");
    }

    @Test
    void balloon_residual_stays_outstanding() {
        LeaseElement element = baseElement("1000000", "0.06", 12).toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();
        Schedule s = new InterestFirstGenerator().generate(context(element));
        assertThat(s.installments().get(11).principalDue()).isEqualByComparingTo("900000.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("100000.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void exposes_code_and_order() {
        InterestFirstGenerator generator = new InterestFirstGenerator();
        assertThat(generator.code()).isEqualTo("INTEREST_FIRST");
        assertThat(generator.order()).isEqualTo(30);
    }

    @Test
    void separate_residual_is_repaid_outside_the_schedule() {
        LeaseElement element = baseElement("1000000", "0.06", 12).toBuilder()
                .residualValue(new BigDecimal("100000"))
                .build();
        Schedule s = new InterestFirstGenerator().generate(context(element));
        assertThat(s.installments().get(11).principalDue()).isEqualByComparingTo("1000000.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void supports_only_interest_first_in_arrears() {
        InterestFirstGenerator generator = new InterestFirstGenerator();
        assertThat(generator.supports(context(baseElement("1000", "0.06", 3)))).isTrue();

        LeaseElement otherMethod = baseElement("1000", "0.06", 3).toBuilder()
                .method(RepaymentMethodId.EQUAL_PRINCIPAL)
                .build();
        assertThat(generator.supports(context(otherMethod))).isFalse();

        LeaseElement advance = baseElement("1000", "0.06", 3).toBuilder()
                .timing(PaymentTiming.IN_ADVANCE)
                .build();
        assertThat(generator.supports(context(advance))).isFalse();
    }

    @Test
    void does_not_support_advance_timing() {
        LeaseElement advance = baseElement("1000000", "0.06", 12).toBuilder()
                .timing(PaymentTiming.IN_ADVANCE)
                .build();
        assertThatThrownBy(() -> new InterestFirstGenerator().generate(context(advance)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-2004");
    }

    private static Schedule generate(String principal, String rate, int periods) {
        LeaseElement element = baseElement(principal, rate, periods);
        return new InterestFirstGenerator().generate(context(element));
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
                .vatRate(new BigDecimal("0.06"))
                .method(RepaymentMethodId.INTEREST_FIRST)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
