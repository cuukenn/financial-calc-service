package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.port.RuleSetView;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.testkit.ReferenceTables.ExpectedMetrics;
import io.github.cuukenn.fincalc.testkit.ReferenceTables.ExpectedRow;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden tests against the literal reference tables of {@link ReferenceTables}: every built-in
 * method is asserted period by period to the cent, and its summary metrics to the pinned total
 * or the allowed root-jitter range. All fixtures use IN_ARREARS (FCS-DOM-2004 rulings R16/R20).
 *
 * <p>Beyond the eight formula-driven method × day-count tables, the source feeds the two Plan
 * 1.5 tables: {@code EQUAL_INSTALLMENT} × {@code SEGMENTED_THIRTY_360} runs the R72 segmented
 * rule rates (1–6 @ 3%, 7–12 @ 6%, RESOLVE) through a real rule set, and
 * {@code EXPLICIT_RENT} × THIRTY_360 carries the equal-installment golden rents verbatim
 * (R69) — the same per-period loop asserts both.
 */
class GoldenScheduleTest {

    @ParameterizedTest(name = "[{index}] {0} x {1}")
    @MethodSource("io.github.cuukenn.fincalc.testkit.ReferenceTables#all")
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
    @MethodSource("io.github.cuukenn.fincalc.testkit.ReferenceTables#metricExpectations")
    void summary_metrics_match_reference(String method, String dayCount, ExpectedMetrics expected) {
        FinancialMetrics m = calculate(method, dayCount).header().metrics();
        assertThat(m.find(MetricCodes.TOTAL_INTEREST)).isEqualByComparingTo(expected.totalInterest());
        assertThat(m.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo(expected.totalRent());
        assertThat(m.find(MetricCodes.IRR_ANNUAL)).isBetween(expected.irrAnnualMin(), expected.irrAnnualMax());
        assertThat(m.find(MetricCodes.XIRR)).isBetween(expected.xirrMin(), expected.xirrMax());
        assertThat(m.find(MetricCodes.DURATION_MACAULAY)).isBetween(expected.durationMin(), expected.durationMax());
    }

    private static Schedule calculate(String method, String dayCount) {
        // SEGMENTED_THIRTY_360 is a table label, not a day-count code: the run itself is a
        // THIRTY_360 element priced through the segmented rule set (R72). EXPLICIT_RENT is
        // assembled on the plain fixture shape because the builder demands the rent sequence
        // up front (FCS-DOM-1008) — the method is switched together with the rents.
        boolean explicit = RepaymentMethodId.EXPLICIT_RENT.name().equals(method);
        String elementMethod = explicit ? "EQUAL_INSTALLMENT" : method;
        String dayCountCode = "SEGMENTED_THIRTY_360".equals(dayCount)
                ? "THIRTY_360" : dayCount;
        LeaseElement element = Fixtures.leaseElement(elementMethod, dayCountCode);
        RuleSetView ruleSet = null;
        if ("SEGMENTED_THIRTY_360".equals(dayCount)) {
            ruleSet = Fixtures.segmentedRateRuleSet(element);
        }
        if (explicit) {
            element = element.toBuilder()
                    .method(RepaymentMethodId.EXPLICIT_RENT)
                    .explicitRents(Fixtures.explicitAnnuityRents())
                    .build();
        }
        return CalculationFacade.defaults().calculate(new CalculationRequest(
                element, ruleSet, CalculationOptions.defaults())).schedule();
    }
}
