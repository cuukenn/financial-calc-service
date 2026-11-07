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
import io.github.cuukenn.fincalc.domain.model.DomainErrorCode;
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

class BulletLoanGeneratorTest {

    @Test
    void defers_principal_and_all_interest_to_maturity() {
        Schedule s = generate("1000000", "0.06", 12);
        for (int i = 0; i < 11; i++) {
            assertThat(s.installments().get(i).principalDue()).isEqualByComparingTo("0.00");
            assertThat(s.installments().get(i).interestAccrued()).isEqualByComparingTo("5000.00");
            assertThat(s.installments().get(i).interestPaid()).isEqualByComparingTo("0.00");
            assertThat(s.installments().get(i).rentDue()).isEqualByComparingTo("0.00");
            assertThat(s.installments().get(i).closingBalance()).isEqualByComparingTo("1000000.00");
        }
        Installment last = s.installments().get(11);
        assertThat(last.principalDue()).isEqualByComparingTo("1000000.00");
        assertThat(last.interestAccrued()).isEqualByComparingTo("5000.00");
        assertThat(last.interestPaid()).isEqualByComparingTo("60000.00");
        assertThat(last.rentDue()).isEqualByComparingTo("1060000.00");
        assertThat(last.closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void total_interest_paid_equals_sum_of_accrued() {
        Schedule s = generate("1000000", "0.06", 12);
        BigDecimal accrued = MoneyUtils.sum(
                s.installments().stream().map(Installment::interestAccrued).collect(toList()));
        BigDecimal paid = MoneyUtils.sum(
                s.installments().stream().map(Installment::interestPaid).collect(toList()));
        BigDecimal principal = MoneyUtils.sum(
                s.installments().stream().map(Installment::principalDue).collect(toList()));
        BigDecimal rent = MoneyUtils.sum(
                s.installments().stream().map(Installment::rentDue).collect(toList()));
        assertThat(paid).isEqualByComparingTo(accrued);
        assertThat(rent).isEqualByComparingTo(principal.add(paid));
    }

    @Test
    void deferred_interest_never_compounds() {
        Schedule s = generate("1000000", "0.06", 12);
        assertThat(s.installments()).allSatisfy(installment -> {
            assertThat(installment.openingBalance()).isEqualByComparingTo("1000000.00");
            assertThat(installment.interestAccrued()).isEqualByComparingTo("5000.00");
        });
    }

    @Test
    void zero_rate_repays_principal_only_at_maturity() {
        Schedule s = generate("1000000", "0", 3);
        assertThat(s.installments().get(0).rentDue()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(1).rentDue()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(2).rentDue()).isEqualByComparingTo("1000000.00");
        assertThat(s.installments().get(2).closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void balloon_residual_stays_outstanding() {
        LeaseElement element = baseElement("1000000", "0.06", 12).toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();
        Schedule s = new BulletLoanGenerator().generate(context(element));
        assertThat(s.installments().get(0).principalDue()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(11).principalDue()).isEqualByComparingTo("900000.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("100000.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void separate_residual_is_repaid_outside_the_schedule() {
        LeaseElement element = baseElement("1000000", "0.06", 12).toBuilder()
                .residualValue(new BigDecimal("100000"))
                .build();
        Schedule s = new BulletLoanGenerator().generate(context(element));
        assertThat(s.installments().get(11).principalDue()).isEqualByComparingTo("1000000.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void supports_only_bullet_loan_in_arrears() {
        BulletLoanGenerator generator = new BulletLoanGenerator();
        assertThat(generator.supports(context(baseElement("1000", "0.06", 3)))).isTrue();

        LeaseElement otherMethod = baseElement("1000", "0.06", 3).toBuilder()
                .method(RepaymentMethodId.INTEREST_FIRST)
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
        assertThatThrownBy(() -> new BulletLoanGenerator().generate(context(advance)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-2004")
                .hasMessageContaining("IN_ADVANCE")
                .satisfies(thrown -> assertThat(((BusinessException) thrown).errorCode())
                        .isEqualTo(DomainErrorCode.UNSUPPORTED_PAYMENT_TIMING));
    }

    @Test
    void exposes_code_and_order() {
        BulletLoanGenerator generator = new BulletLoanGenerator();
        assertThat(generator.code()).isEqualTo("BULLET_LOAN");
        assertThat(generator.order()).isEqualTo(45);
    }

    private static Schedule generate(String principal, String rate, int periods) {
        return new BulletLoanGenerator()
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
                .vatRate(new BigDecimal("0.06"))
                .method(RepaymentMethodId.BULLET_LOAN)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
