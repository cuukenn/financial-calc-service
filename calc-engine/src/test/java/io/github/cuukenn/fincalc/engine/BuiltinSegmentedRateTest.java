package io.github.cuukenn.fincalc.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PrincipalPlanKind;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.rule.compiler.RuleCompiler;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import io.github.cuukenn.fincalc.rule.model.CompiledRuleSet;
import io.github.cuukenn.fincalc.rule.model.RuleRow;

/**
 * R70 integration tests: the built-in (non-RULE_DRIVEN) generators consume rule-resolved
 * per-period rates through the {@code CalculationContext.ruleSet} view the facade threads from
 * the request. The base template resolves each period through the {@code RuleSetView} port's
 * 4-arg resolve — unconditional rows govern the rate, conditioned rows are skipped there
 * (Task 26 honest-no-match semantics; conditioned rates need RULE_DRIVEN) — and falls back to
 * the element's nominal rate where the rules leave the rate open.
 *
 * <p>R17's uniform-rate guard ({@code FCS-DOM-2002}) fires only when <em>no</em> rule set is
 * present: with a rule set the per-period rates may legitimately vary, so a stub window must
 * not block the run. The no-rule-set guard posture itself stays pinned by
 * {@code EqualInstallmentGeneratorTest.rejects_non_uniform_period_rates_instead_of_emitting_negative_balances}.
 */
class BuiltinSegmentedRateTest {

    @Test
    void equal_principal_applies_rule_resolved_segmented_rates() {
        LeaseElement element = baseElement(RepaymentMethodId.EQUAL_PRINCIPAL);
        CompiledRuleSet set = compiler().compile(List.of(
                rateRow("R1", 10, 1, 6, "0.03"),
                rateRow("R2", 20, 7, 12, "0.06")), element, false);

        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(element, set, CalculationOptions.defaults()));

        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.schedule().installments().get(0).appliedRate())
                .isEqualByComparingTo("0.03");
        assertThat(r.schedule().installments().get(5).appliedRate())
                .isEqualByComparingTo("0.03");
        assertThat(r.schedule().installments().get(6).appliedRate())
                .isEqualByComparingTo("0.06");
        assertThat(r.schedule().installments().get(11).appliedRate())
                .isEqualByComparingTo("0.06");
        // Segmented rate drives the accrual: 1 000 000 x 0.03 / 12 = 2 500.00 in period 1
        // against the golden 5 000.00 at the 6% nominal rate.
        assertThat(r.schedule().installments().get(0).interestAccrued())
                .isEqualByComparingTo("2500.00");
    }

    @Test
    void equal_installment_builds_with_uniform_rule_rates() {
        LeaseElement element = baseElement(RepaymentMethodId.EQUAL_INSTALLMENT);
        CompiledRuleSet set = compiler().compile(
                List.of(rateRow("R1", 10, 1, 12, "0.06")), element, false);

        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(element, set, CalculationOptions.defaults()));

        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.schedule().installments()).hasSize(12);
        assertThat(r.schedule().installments()).allSatisfy(i ->
                assertThat(i.appliedRate()).isEqualByComparingTo("0.06"));
        assertThat(r.schedule().installments().get(11).closingBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void equal_installment_with_rule_set_skips_the_uniform_rate_guard() {
        // A 6-month stub first window makes the nominal-derived periodic rates non-uniform, so
        // the pure built-in run would fail with FCS-DOM-2002. R70: with a rule set present the
        // rates come from the rules (uniform 0.06 here) and the guard must not fire.
        LeaseElement element = baseElement(RepaymentMethodId.EQUAL_INSTALLMENT)
                .toBuilder()
                .firstRentDate(LocalDate.of(2024, 7, 1))
                .build();
        CompiledRuleSet set = compiler().compile(
                List.of(rateRow("R1", 10, 1, 12, "0.06")), element, false);

        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(element, set, CalculationOptions.defaults()));

        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.schedule().installments()).hasSize(12);
        assertThat(r.schedule().installments()).allSatisfy(i ->
                assertThat(i.appliedRate()).isEqualByComparingTo("0.06"));
        assertThat(r.schedule().installments().get(11).closingBalance())
                .isEqualByComparingTo("0.00");
    }

    // ---- Helpers (R6: per-class fixtures; mirror CalculationFacadeTest) ----

    private static RuleCompiler compiler() {
        return new RuleCompiler(FunctionRegistry.defaults());
    }

    private static LeaseElement baseElement(RepaymentMethodId method) {
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
                .method(method)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }

    private static RuleRow rateRow(String ruleId, int priority, int from, int to, String rate) {
        return new RuleRow(ruleId, priority, from, to, new BigDecimal(rate), null,
                PrincipalPlanKind.INHERIT, null, null,
                io.github.cuukenn.fincalc.domain.model.InterestTreatmentKind.PAY_AS_INCURRED,
                PaymentTiming.IN_ARREARS,
                io.github.cuukenn.fincalc.domain.model.SpecialActionKind.NONE, null, 0, null,
                null, 0, null);
    }
}
