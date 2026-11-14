package io.github.cuukenn.fincalc.server;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves the R78 minimal assembly: {@link CalculationFacade#defaults()} is a Spring bean and the
 * whole engine classpath resolves inside the context — one trivial run through the injected bean
 * produces the expected schedule.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CalculationFacadeBeanWiringTest {

    @Autowired
    private CalculationFacade facade;

    @Test
    void facade_defaults_is_an_injectable_bean() {
        assertNotNull(facade);
    }

    @Test
    void facade_runs_a_trivial_equal_installment_calculation() {
        LeaseElement element = LeaseElement.builder()
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
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();

        CalculationResult result = facade.calculate(new CalculationRequest(element, null, null));

        assertNotNull(result.runId());
        assertEquals(12, result.schedule().installments().size());
    }
}
