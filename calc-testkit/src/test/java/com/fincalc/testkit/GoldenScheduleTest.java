package com.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fincalc.domain.metric.FinancialMetrics;
import com.fincalc.domain.metric.MetricCodes;
import com.fincalc.domain.model.Schedule;
import com.fincalc.engine.CalculationFacade;
import com.fincalc.engine.CalculationRequest;
import com.fincalc.domain.model.CalculationOptions;
import com.fincalc.testkit.ReferenceTables.ExpectedMetrics;
import com.fincalc.testkit.ReferenceTables.ExpectedRow;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden tests against the literal reference tables of {@link ReferenceTables}: every built-in
 * method is asserted period by period to the cent, and its summary metrics to the pinned total
 * or the allowed root-jitter range. All fixtures use IN_ARREARS (FCS-DOM-2004 rulings R16/R20).
 */
class GoldenScheduleTest {

    @ParameterizedTest(name = "[{index}] {0} x {1}")
    @MethodSource("com.fincalc.testkit.ReferenceTables#all")
    void every_method_matches_reference_table_to_the_cent(String method, String dayCount,
            List<ExpectedRow> expected) {
        Schedule s = calculate(method, dayCount);
        assertThat(s.installments()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            ExpectedRow e = expected.get(i);
            var a = s.installments().get(i);
            assertThat(a.periodNo()).as("periodNo of row %d", i).isEqualTo(e.periodNo());
            assertThat(a.openingBalance()).as("opening of period %d", e.periodNo())
                    .isEqualByComparingTo(e.openingBalance());
            assertThat(a.principalDue()).as("principal of period %d", e.periodNo())
                    .isEqualByComparingTo(e.principalDue());
            assertThat(a.interestAccrued()).as("interest of period %d", e.periodNo())
                    .isEqualByComparingTo(e.interestAccrued());
            assertThat(a.rentDue()).as("rent of period %d", e.periodNo())
                    .isEqualByComparingTo(e.rentDue());
            assertThat(a.closingBalance()).as("closing of period %d", e.periodNo())
                    .isEqualByComparingTo(e.closingBalance());
        }
    }

    @ParameterizedTest(name = "[{index}] {0} x {1}")
    @MethodSource("com.fincalc.testkit.ReferenceTables#metricExpectations")
    void summary_metrics_match_reference(String method, String dayCount, ExpectedMetrics expected) {
        FinancialMetrics m = calculate(method, dayCount).header().metrics();
        assertThat(m.find(MetricCodes.TOTAL_INTEREST)).isEqualByComparingTo(expected.totalInterest());
        assertThat(m.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo(expected.totalRent());
        assertThat(m.find(MetricCodes.IRR_ANNUAL)).isBetween(expected.irrAnnualMin(), expected.irrAnnualMax());
        assertThat(m.find(MetricCodes.XIRR)).isBetween(expected.xirrMin(), expected.xirrMax());
        assertThat(m.find(MetricCodes.DURATION_MACAULAY)).isBetween(expected.durationMin(), expected.durationMax());
    }

    private static Schedule calculate(String method, String dayCount) {
        return CalculationFacade.defaults().calculate(new CalculationRequest(
                Fixtures.leaseElement(method, dayCount), null, CalculationOptions.defaults()))
                .schedule();
    }
}
