package io.github.cuukenn.fincalc.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseElementTest {

    @Test
    void builder_rejects_non_positive_principal() {
        assertThatThrownBy(() -> baseBuilder().principal(BigDecimal.ZERO).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM");
    }

    @Test
    void builder_rejects_rate_above_one() {
        assertThatThrownBy(() -> baseBuilder().nominalRate(new BigDecimal("1.5")).build())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void irregular_requires_explicit_periods() {
        assertThatThrownBy(() -> baseBuilder()
                .frequency(PaymentFrequency.IRREGULAR)
                .explicitPeriods(Collections.emptyList())
                .build())
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void regular_derives_periods_per_year() {
        assertThat(PaymentFrequency.QUARTERLY.periodsPerYear()).isEqualTo(4);
        assertThat(PaymentFrequency.MONTHLY.monthsPerPeriod()).isEqualTo(1);
    }

    @Test
    void explicit_rent_requires_rents() {
        assertThatThrownBy(() -> baseBuilder().method(RepaymentMethodId.EXPLICIT_RENT).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1008");
    }

    @Test
    void explicit_rent_rejects_wrong_rent_count() {
        assertThatThrownBy(() -> baseBuilder()
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .explicitRents(twelveRents().subList(0, 11))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1008");
    }

    @Test
    void explicit_rent_irregular_rejects_wrong_rent_count() {
        assertThatThrownBy(() -> baseBuilder()
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .frequency(PaymentFrequency.IRREGULAR)
                .explicitPeriods(java.util.Arrays.asList(
                        PeriodDate.builder().startDate(LocalDate.of(2024, 1, 1))
                                .endDate(LocalDate.of(2024, 2, 1)).build(),
                        PeriodDate.builder().startDate(LocalDate.of(2024, 2, 1))
                                .endDate(LocalDate.of(2024, 3, 1)).build()))
                .explicitRents(twelveRents())
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1008");
    }

    @Test
    void explicit_rent_rejects_non_positive_rent() {
        java.util.List<BigDecimal> rents = twelveRents();
        rents.set(3, BigDecimal.ZERO);
        assertThatThrownBy(() -> baseBuilder()
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .explicitRents(rents)
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1009");
    }

    @Test
    void non_explicit_method_rejects_explicit_rents() {
        assertThatThrownBy(() -> baseBuilder().explicitRents(twelveRents()).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1008");
    }

    @Test
    void builder_rejects_periods_above_hard_limit() {
        assertThatThrownBy(() -> baseBuilder().periods(1201).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1010")
                .hasMessageContaining("1201");
    }

    @Test
    void builder_accepts_periods_at_hard_limit() {
        LeaseElement element = baseBuilder().periods(1200).build();
        assertThat(element.periods()).isEqualTo(1200);
    }

    @Test
    void builder_rejects_irregular_explicit_periods_above_hard_limit() {
        List<PeriodDate> periods = new ArrayList<>();
        for (int i = 0; i < 1201; i++) {
            periods.add(PeriodDate.builder()
                    .startDate(LocalDate.of(2024, 1, 1).plusDays(i * 30L))
                    .endDate(LocalDate.of(2024, 1, 1).plusDays(i * 30L + 1))
                    .build());
        }
        assertThatThrownBy(() -> baseBuilder()
                .frequency(PaymentFrequency.IRREGULAR)
                .explicitPeriods(periods)
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1010")
                .hasMessageContaining("1201");
    }

    @Test
    void builder_rejects_negative_fee_amount() {
        assertThatThrownBy(() -> baseBuilder()
                .fees(Collections.singletonList(FeeItem.builder()
                        .type("HANDLING")
                        .amount(new BigDecimal("-1"))
                        .chargeMode(FeeChargeMode.UPFRONT)
                        .includeInIrr(true)
                        .build()))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1011")
                .hasMessageContaining("-1");
    }

    @Test
    void builder_rejects_null_fee_amount() {
        assertThatThrownBy(() -> baseBuilder()
                .fees(Collections.singletonList(FeeItem.builder()
                        .type("HANDLING")
                        .amount(null)
                        .chargeMode(FeeChargeMode.UPFRONT)
                        .includeInIrr(true)
                        .build()))
                .build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1011");
    }

    @Test
    void builder_rejects_negative_deposit() {
        assertThatThrownBy(() -> baseBuilder().deposit(new BigDecimal("-1")).build())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-1012")
                .hasMessageContaining("-1");
    }

    @Test
    void builder_accepts_zero_fee_amount() {
        LeaseElement element = baseBuilder()
                .fees(Collections.singletonList(FeeItem.builder()
                        .type("HANDLING")
                        .amount(BigDecimal.ZERO)
                        .chargeMode(FeeChargeMode.UPFRONT)
                        .includeInIrr(false)
                        .build()))
                .build();
        assertThat(element.fees()).hasSize(1);
    }

    @Test
    void builder_accepts_null_deposit() {
        LeaseElement element = baseBuilder().deposit(null).build();
        assertThat(element.deposit()).isNull();
    }

    @Test
    void explicit_rent_happy_path_builds_unmodifiable_rents() {
        LeaseElement element = baseBuilder()
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .explicitRents(twelveRents())
                .build();
        assertThat(element.explicitRents()).hasSize(12);
        assertThat(element.explicitRents())
                .isUnmodifiable()
                .containsExactlyElementsOf(twelveRents());
        assertThat(element.toBuilder().build().explicitRents()).hasSize(12);
    }

    private static java.util.List<BigDecimal> twelveRents() {
        java.util.List<BigDecimal> rents = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rents.add(new BigDecimal("90000"));
        }
        return rents;
    }

    private LeaseElement.Builder baseBuilder() {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000"))
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(LocalDate.of(2024, 1, 1))
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
                .fees(Collections.emptyList())
                .vatRate(null)
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE);
    }
}
