package io.github.cuukenn.fincalc.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PeriodDate;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PeriodBuilderTest {

    @Test
    void builds_twelve_monthly_periods_arrears() {
        List<PeriodDate> periods = PeriodBuilder.build(baseElement(), DateRollConventions.all().require("NONE"));
        assertThat(periods).hasSize(12);
        assertThat(periods.get(0).startDate()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(periods.get(0).endDate()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(periods.get(0).paymentDate()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(periods.get(11).endDate()).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    void advance_timing_pays_at_period_start() {
        List<PeriodDate> periods = PeriodBuilder.build(
                baseElement().toBuilder().timing(PaymentTiming.IN_ADVANCE).build(),
                DateRollConventions.all().require("NONE"));
        assertThat(periods.get(0).paymentDate()).isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    void monthly_on_31st_does_not_drift() {
        // 起租 2024-01-31，首期 2024-02-29
        List<PeriodDate> periods = PeriodBuilder.build(eomElement(),
                DateRollConventions.all().require("END_OF_MONTH"));
        assertThat(periods.get(0).endDate()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(periods.get(1).endDate()).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(periods.get(11).endDate()).isEqualTo(LocalDate.of(2025, 1, 31));
    }

    @Test
    void rejects_non_monotonic_explicit_periods() {
        assertThatThrownBy(() -> PeriodBuilder.build(irregularWithOverlap(),
                DateRollConventions.all().require("NONE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-DOM-2001");
    }

    private static LeaseElement baseElement() {
        return builder()
                .commenceDate(LocalDate.of(2024, 1, 1))
                .firstRentDate(LocalDate.of(2024, 2, 1))
                .build();
    }

    private static LeaseElement eomElement() {
        return builder()
                .commenceDate(LocalDate.of(2024, 1, 31))
                .firstRentDate(LocalDate.of(2024, 2, 29))
                .build();
    }

    private static LeaseElement irregularWithOverlap() {
        return builder()
                .commenceDate(LocalDate.of(2024, 1, 1))
                .firstRentDate(LocalDate.of(2024, 3, 1))
                .periods(2)
                .frequency(PaymentFrequency.IRREGULAR)
                .explicitPeriods(List.of(
                        period(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 1)),
                        period(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 4, 1))))
                .build();
    }

    private static PeriodDate period(LocalDate start, LocalDate end) {
        return PeriodDate.builder().startDate(start).endDate(end).paymentDate(end).build();
    }

    private static LeaseElement.Builder builder() {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000"))
                .nominalRate(new BigDecimal("0.06"))
                .periods(12)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualValue(BigDecimal.ZERO)
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(BigDecimal.ZERO)
                .vatRate(BigDecimal.ZERO)
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE);
    }
}
