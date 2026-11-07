package io.github.cuukenn.fincalc.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * End-to-end smoke test (Task 31): one full engine run from a plain lease element through
 * {@link CalculationFacade#defaults()} to the enriched schedule, financial metrics, IRR cash
 * flows and the §9.7 effective-interest table.
 *
 * <p>Brief corrections (binding): the calc-testkit {@code Fixtures} helper is not visible to
 * calc-engine tests (calc-testkit depends on calc-engine, not the other way around — R4), so the
 * standard lease element is built inline below, mirroring the Task-21 fixture. The
 * {@code TOTAL_RENT} golden is 1 032 797.15 per the Task-30 reference table (the brief snippet's
 * .16 predates the Task-21 tail-adjustment correction). No text blocks — the build targets JDK 11.
 */
class EndToEndSmokeTest {

    @Test
    void calculates_full_schedule_from_code_and_prints_summary() {
        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(standardLease(), null, CalculationOptions.defaults()));

        FinancialMetrics m = r.schedule().header().metrics();

        assertThat(r.schedule().installments()).hasSize(12);
        assertThat(m.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.15");
        assertThat(m.find(MetricCodes.IRR_PERIOD)).isNotNull();
        assertThat(m.find(MetricCodes.IRR_ANNUAL)).isNotNull();
        assertThat(m.find(MetricCodes.XIRR)).isNotNull();
        assertThat(m.find(MetricCodes.DURATION_MACAULAY)).isNotNull();
        assertThat(r.schedule().cashFlows()).isNotEmpty();
        assertThat(r.effectiveInterest()).isNotNull();
        assertThat(r.effectiveInterest().rows()).hasSize(12);
        assertThat(r.schedule().hasErrors()).isFalse();

        System.out.printf(
                "smoke: %d installments, TOTAL_RENT=%s, IRR_PERIOD=%s, XIRR=%s%n",
                r.schedule().installments().size(),
                m.find(MetricCodes.TOTAL_RENT),
                m.find(MetricCodes.IRR_PERIOD),
                m.find(MetricCodes.XIRR));
    }

    /**
     * The Task-21 standard lease fixture, built inline: 12 monthly periods in arrears,
     * principal 1 000 000 at a 6% nominal annual rate on THIRTY_360, equal-installment rent,
     * a separate (here zero) residual, no deposit or fees, default rounding, no grace, CNY.
     */
    private static LeaseElement standardLease() {
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
                .vatRate(new BigDecimal("0.06"))
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
