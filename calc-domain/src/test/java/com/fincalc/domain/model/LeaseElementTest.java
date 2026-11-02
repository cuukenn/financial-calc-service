package com.fincalc.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;

import com.fincalc.common.error.BusinessException;
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
