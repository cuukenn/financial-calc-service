package io.github.cuukenn.fincalc.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

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

/**
 * End-to-end wiring of the {@code EXPLICIT_RENT} route (R69): the facade routes the method
 * through {@link GeneratorRegistry#defaults()} (before this run registered the generator,
 * {@code require("EXPLICIT_RENT")} failed with {@code FCS-ENG-1002}), the generator splits the
 * given rents and the orchestration enriches metrics, cash flows and the effective-interest
 * table exactly as for the formula-driven built-ins — the facade needed no new branch beyond
 * the registry entry.
 *
 * <p>The rent fixture is the equal-installment annuity of the {@code CalculationFacadeTest}
 * golden (11 x 86 066.43 plus the tail-adjusted 86 066.42, total 1 032 797.15): given the same
 * rents and rates, the explicit-rent split must reproduce the equal-installment schedule
 * exactly, which cross-checks the R69 split against the established golden without a new one.
 */
class ExplicitRentRouteTest {

    @Test
    void require_resolves_explicit_rent() {
        assertThat(GeneratorRegistry.defaults().require("EXPLICIT_RENT").code())
                .isEqualTo("EXPLICIT_RENT");
    }

    @Test
    void routes_explicit_rent_end_to_end() {
        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(element(), null, CalculationOptions.defaults()));

        assertThat(r.schedule().installments()).hasSize(12);
        List<BigDecimal> rents = rents();
        for (int i = 0; i < rents.size(); i++) {
            assertThat(r.schedule().installments().get(i).rentDue())
                    .isEqualByComparingTo(rents.get(i));
        }
        // The annuity rents amortize exactly, so the conservation validators stay silent.
        assertThat(r.schedule().installments().get(11).closingBalance())
                .isEqualByComparingTo("0.00");
        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.schedule().header().metrics().find(MetricCodes.TOTAL_RENT))
                .isEqualByComparingTo("1032797.15");
        assertThat(r.schedule().header().metrics().find(MetricCodes.IRR_PERIOD)).isNotNull();
        assertThat(r.schedule().cashFlows()).isNotEmpty();
    }

    /** The CalculationFacadeTest annuity golden: 11 x 86 066.43 plus a tail of 86 066.42. */
    private static List<BigDecimal> rents() {
        List<BigDecimal> rents = new ArrayList<>(12);
        for (int i = 0; i < 11; i++) {
            rents.add(new BigDecimal("86066.43"));
        }
        rents.add(new BigDecimal("86066.42"));
        return rents;
    }

    private static LeaseElement element() {
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
                .method(RepaymentMethodId.EXPLICIT_RENT)
                .explicitRents(rents())
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }
}
