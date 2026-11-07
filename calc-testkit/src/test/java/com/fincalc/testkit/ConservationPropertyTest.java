package com.fincalc.testkit;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.model.CalculationOptions;
import com.fincalc.domain.model.Installment;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.Schedule;
import com.fincalc.engine.CalculationFacade;
import com.fincalc.engine.CalculationRequest;
import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;

/**
 * Conservation properties of the calculation engine (jqwik, TEST scope per R7). The space
 * deliberately includes the corners: rate 0 (zero-rate branch of both the closed form and the
 * iterative solver) and periods 1 (single-period schedules where every allocation collapses
 * into the tail period).
 */
class ConservationPropertyTest {

    @Property(tries = 500)
    void principal_is_always_conserved(
            @ForAll @BigRange(min = "1000", max = "10000000") BigDecimal principal,
            @ForAll @BigRange(min = "0", max = "0.3") BigDecimal rate,
            @ForAll @IntRange(min = 1, max = 120) int periods) {
        Schedule s = calculate(principal, rate, periods, "EQUAL_INSTALLMENT", "THIRTY_360");
        assertThat(MoneyUtils.sum(s.installments().stream()
                .map(Installment::principalDue).collect(toList())))
                .isEqualByComparingTo(MoneyUtils.out(principal));
        assertThat(s.installments().get(periods - 1).closingBalance())
                .isEqualByComparingTo("0.00");
        assertThat(s.installments()).allSatisfy(i ->
                assertThat(i.closingBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO));
    }

    @Property(tries = 300)
    void single_period_and_zero_rate_never_throw(
            @ForAll @BigRange(min = "1000", max = "1000000") BigDecimal principal,
            @ForAll @BigRange(min = "0", max = "0") BigDecimal rate) {
        assertThatCode(() -> calculate(principal, rate, 1, "EQUAL_INSTALLMENT", "ACT_365F"))
                .doesNotThrowAnyException();
    }

    private static Schedule calculate(BigDecimal principal, BigDecimal rate, int periods,
            String method, String dayCount) {
        LeaseElement element = Fixtures.leaseElement(principal, rate, periods, method, dayCount);
        return CalculationFacade.defaults()
                .calculate(new CalculationRequest(element, null, CalculationOptions.defaults()))
                .schedule();
    }
}
