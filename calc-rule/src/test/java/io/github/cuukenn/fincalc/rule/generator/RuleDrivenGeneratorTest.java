package io.github.cuukenn.fincalc.rule.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.InterestTreatmentKind;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PrincipalPlanKind;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.SpecialActionKind;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.support.DateRollConventions;
import io.github.cuukenn.fincalc.domain.support.DayCountConventions;
import io.github.cuukenn.fincalc.rule.RuleErrorCode;
import io.github.cuukenn.fincalc.rule.compiler.RuleCompiler;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import io.github.cuukenn.fincalc.rule.model.CompiledRuleSet;
import io.github.cuukenn.fincalc.rule.model.RuleRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests of the rule-driven schedule generator (Task 28). The three brief Step 1 tests are
 * verbatim; the remaining tests pin the binding rulings: R15 (uncovered period blocks by
 * default, falls back with a {@code RULE_FALLBACK} marker when
 * {@code allowPartialRuleSet} is on), the INHERIT fallback's method restriction
 * ({@code FCS-RULE-2007}), the R17 negative-principal backstop, and the arch-seams 收口
 * (2026-11-14) rule that the fallback WARN ({@code FCS-RULE-2009}) is emitted here, on the
 * schedule, not re-derived by the engine from the memo strings.
 */
class RuleDrivenGeneratorTest {

    private static final BigDecimal PRINCIPAL = new BigDecimal("1000000");

    // ---- Brief Step 1 tests, verbatim ----

    @Test
    void applies_segmented_rates() {
        CompiledRuleSet set = compiler().compile(List.of(
                rateRow("R1", 10, 1, 6, "0.03"),
                rateRow("R2", 20, 7, 12, "0.06")), element(12), false);
        Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement().toBuilder()
                .method(RepaymentMethodId.RULE_DRIVEN).build()));

        assertThat(s.installments().get(0).appliedRate()).isEqualByComparingTo("0.03");
        assertThat(s.installments().get(6).appliedRate()).isEqualByComparingTo("0.06");
        assertThat(s.installments()).allSatisfy(i ->
                assertThat(i.memo()).startsWith("R"));
    }

    @Test
    void supports_none_principal_periods() {
        CompiledRuleSet set = compiler().compile(List.of(
                principalRow("R1", 10, 1, 6, PrincipalPlanKind.NONE, "0"),
                principalRow("R2", 20, 7, 12, PrincipalPlanKind.EQUAL, "0")), element(12), false);
        Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement().toBuilder()
                .method(RepaymentMethodId.RULE_DRIVEN).build()));

        assertThat(s.installments().get(0).principalDue()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void last_period_absorbs_remaining_principal() {
        CompiledRuleSet set = compiler().compile(List.of(
                rateRow("R1", 10, 1, 12, "0.06")), element(12), false);
        Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement().toBuilder()
                .method(RepaymentMethodId.RULE_DRIVEN).build()));
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
    }

    // ---- R15 / design §8.7: uncovered period blocks by default ----

    @Test
    void uncovered_period_blocks_by_default() {
        // allowPartial=true is needed to compile a set with a gap at all (compiler V3);
        // the generator must still refuse to run it under default options.
        CompiledRuleSet set = compiler().compile(List.of(
                rateRow("R1", 10, 1, 6, "0.06")), element(12), true);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new RuleDrivenGenerator(set).generate(context(baseElement())));
        assertThat(ex.errorCode().code()).isEqualTo("FCS-RULE-2001");
        assertThat(ex.getMessage()).contains("第 7 期");
    }

    @Test
    void uncovered_period_falls_back_with_marker_when_allowed() {
        CompiledRuleSet set = compiler().compile(List.of(
                rateRow("R1", 10, 1, 6, "0.06")), element(12), true);
        Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement(),
                new CalculationOptions(true,
                        io.github.cuukenn.fincalc.domain.model.CashFlowBasis.INCLUDE_FEES)));

        assertThat(s.installments().get(5).memo()).isEqualTo("R1");
        assertThat(s.installments().get(6).memo()).isEqualTo("RULE_FALLBACK");
        assertThat(s.installments().get(6).principalDue()).isEqualByComparingTo("0.00");
        assertThat(s.installments().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(s.hasErrors()).isFalse();
    }

    // ---- arch-seams 收口（2026-11-14）：fallback WARN 由规则层直接产出，不再由 engine 扫 memo ----

    @Test
    void fallback_periods_are_disclosed_as_warnings_on_the_schedule() {
        CompiledRuleSet set = compiler().compile(List.of(
                rateRow("R1", 10, 1, 6, "0.06")), element(12), true);
        Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement(),
                new CalculationOptions(true,
                        io.github.cuukenn.fincalc.domain.model.CashFlowBasis.INCLUDE_FEES)));

        // 期 7..12 未被覆盖：每期一条 WARN，期号写进 issue，memo 保持 R15 的 RULE_FALLBACK 标记。
        assertThat(s.issues())
                .filteredOn(i -> RuleErrorCode.RULE_FALLBACK_PERIOD.code().equals(i.code()))
                .hasSize(6)
                .allSatisfy(i -> {
                    assertThat(i.level()).isEqualTo(IssueLevel.WARN);
                    assertThat(i.periodNo()).isBetween(7, 12);
                    assertThat(i.message()).contains("期未被规则集覆盖");
                });
        assertThat(s.installments().get(6).memo()).isEqualTo("RULE_FALLBACK");
        assertThat(s.hasErrors()).isFalse();
    }

    @Test
    void fully_covered_schedule_carries_no_fallback_warning() {
        CompiledRuleSet set = compiler().compile(List.of(
                rateRow("R1", 10, 1, 12, "0.06")), element(12), false);
        Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement()));

        assertThat(s.issues()).noneMatch(i ->
                RuleErrorCode.RULE_FALLBACK_PERIOD.code().equals(i.code()));
    }

    // ---- INHERIT fallback: only EQUAL_PRINCIPAL / RULE_DRIVEN methods are supported ----

    @Test
    void inherit_plan_with_unsupported_method_is_rejected() {
        CompiledRuleSet set = compiler().compile(List.of(
                rateRow("R1", 10, 1, 12, "0.06")), element(12), false);
        LeaseElement element = baseElement().toBuilder()
                .method(RepaymentMethodId.EQUAL_INSTALLMENT).build();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new RuleDrivenGenerator(set).generate(context(element)));
        assertThat(ex.errorCode()).isEqualTo(RuleErrorCode.UNSUPPORTED_INHERIT_METHOD);
    }

    // ---- R17 backstop: a plan that would drive the balance negative fails loudly ----

    @Test
    void negative_principal_plan_fails_loudly() {
        // The INHERIT row makes the compiler's deterministic-total check (V5) stay silent, so
        // the over-paying FIXED row reaches the generator and must hit the backstop at period 6.
        CompiledRuleSet set = compiler().compile(List.of(
                principalRow("R1", 10, 1, 6, PrincipalPlanKind.FIXED, "200000"),
                principalRow("R2", 20, 7, 12, PrincipalPlanKind.INHERIT, "0")), element(12), false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new RuleDrivenGenerator(set).generate(context(baseElement())));
        assertThat(ex.errorCode().code()).isEqualTo("FCS-DOM-2003");
        assertThat(ex.getMessage()).contains("第 6 期");
    }

    // ---- Corrupt plan guard: FIXED without its value (V5 is skipped when INHERIT rows exist) ----

    @Test
    void fixed_plan_without_value_fails_loudly() {
        CompiledRuleSet set = compiler().compile(List.of(
                builder("R1", 10, 1, 6).plan(PrincipalPlanKind.FIXED).build(),
                builder("R2", 20, 7, 12).build()), element(12), false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new RuleDrivenGenerator(set).generate(context(baseElement())));
        assertThat(ex.errorCode()).isEqualTo(RuleErrorCode.PRINCIPAL_PLAN_TOTAL);
    }

    // ---- Conditions (design §8.2 column D): the wired EvalContext drives period-specific rates ----

    @Test
    void conditioned_rules_drive_period_specific_rates() {
        // RATE carries the element's nominal (fallback) rate 0.06, so the condition holds for
        // periods 1..6 only; periods 7..12 fall through to the unconditional lower-priority row.
        CompiledRuleSet set = compiler().compile(List.of(
                builder("R1", 10, 1, 12).rate("0.03")
                        .condition("PERIOD <= 6 && RATE > 0.04").build(),
                rateRow("R2", 20, 1, 12, "0.06")), element(12), false);
        Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement().toBuilder()
                .method(RepaymentMethodId.RULE_DRIVEN).build()));

        assertThat(s.installments().get(0).appliedRate()).isEqualByComparingTo("0.03");
        assertThat(s.installments().get(5).appliedRate()).isEqualByComparingTo("0.03");
        assertThat(s.installments().get(6).appliedRate()).isEqualByComparingTo("0.06");
        assertThat(s.installments().get(11).appliedRate()).isEqualByComparingTo("0.06");
    }

    @Test
    void is_last_and_residual_variables_evaluate_in_conditions() {
        LeaseElement balloon = baseElement().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();
        CompiledRuleSet set = compiler().compile(List.of(
                builder("R0", 5, 12, 12).rate("0.03")
                        .condition("IS_LAST && RESIDUAL >= 100000").build(),
                rateRow("R1", 10, 1, 12, "0.06")), balloon, false);
        Schedule s = new RuleDrivenGenerator(set).generate(context(balloon));

        assertThat(s.installments().get(11).appliedRate()).isEqualByComparingTo("0.03");
        assertThat(s.installments().get(10).appliedRate()).isEqualByComparingTo("0.06");
    }

    @Test
    void blank_residual_reads_as_empty_cell_in_conditions() {
        // The base element's residual is 0 under SEPARATE, so RESIDUAL > 0 fails everywhere and
        // only the unconditional row can match.
        CompiledRuleSet set = compiler().compile(List.of(
                builder("R0", 5, 1, 12).rate("0.03").condition("RESIDUAL > 0").build(),
                rateRow("R1", 10, 1, 12, "0.06")), element(12), false);
        Schedule s = new RuleDrivenGenerator(set).generate(context(baseElement().toBuilder()
                .method(RepaymentMethodId.RULE_DRIVEN).build()));

        assertThat(s.installments()).allSatisfy(i ->
                assertThat(i.appliedRate()).isEqualByComparingTo("0.06"));
    }

    @Test
    void condition_false_everywhere_leaves_periods_uncovered() {
        CompiledRuleSet set = compiler().compile(List.of(
                builder("R1", 10, 1, 12).rate("0.06").condition("PERIOD > 100").build()),
                element(12), false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new RuleDrivenGenerator(set).generate(context(baseElement())));
        assertThat(ex.errorCode().code()).isEqualTo("FCS-RULE-2001");
        assertThat(ex.getMessage()).contains("第 1 期");
    }

    // ---- R65: special actions other than NONE fail generation loudly (design §8.2.2) ----

    @Test
    void fee_amortize_action_is_rejected_loudly() {
        // V8 accepts FEE_AMORTIZE at compile time; the generator must not silently ignore it.
        CompiledRuleSet set = compiler().compile(List.of(
                builder("R1", 10, 1, 12).rate("0.06")
                        .action(SpecialActionKind.FEE_AMORTIZE).build()),
                element(12), false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> new RuleDrivenGenerator(set).generate(context(baseElement())));
        assertThat(ex.errorCode().code()).isEqualTo("FCS-RULE-2005");
        assertThat(ex.getMessage()).contains("FEE_AMORTIZE");
    }

    // ---- Identity ----

    @Test
    void exposes_code_and_order() {
        RuleDrivenGenerator generator = new RuleDrivenGenerator(compiler().compile(
                List.of(rateRow("R1", 10, 1, 12, "0.06")), element(12), false));
        assertThat(generator.code()).isEqualTo("RULE_DRIVEN");
        assertThat(generator.order()).isEqualTo(100);
        assertThat(generator.supports(context(baseElement()))).isTrue();
    }

    // ---- Helpers (R6: per-class fixtures; mirror the calc-domain generator tests) ----

    private static RuleCompiler compiler() {
        return new RuleCompiler(FunctionRegistry.defaults());
    }

    private static LeaseElement baseElement() {
        return LeaseElement.builder()
                .principal(PRINCIPAL)
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(LocalDate.of(2026, 1, 1))
                .firstRentDate(LocalDate.of(2026, 2, 1))
                .periods(12)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualValue(BigDecimal.ZERO)
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(BigDecimal.ZERO)
                .method(RepaymentMethodId.RULE_DRIVEN)
                .gracePeriod(GracePeriod.none())
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }

    private static LeaseElement element(int periods) {
        return baseElement().toBuilder().periods(periods).build();
    }

    private static CalculationContext context(LeaseElement element) {
        return context(element, CalculationOptions.defaults());
    }

    private static CalculationContext context(LeaseElement element, CalculationOptions options) {
        return new CalculationContext(element, null,
                DayCountConventions.all().require("THIRTY_360"),
                DateRollConventions.all().require("NONE"),
                SystemClockProvider.INSTANCE, options);
    }

    private static RuleRowBuilder builder(String ruleId, int priority, int from, int to) {
        return new RuleRowBuilder(ruleId, priority, from, to);
    }

    private static RuleRow rateRow(String ruleId, int priority, int from, int to, String rate) {
        return builder(ruleId, priority, from, to).rate(rate).build();
    }

    private static RuleRow principalRow(String ruleId, int priority, int from, int to,
            PrincipalPlanKind plan, String value) {
        return builder(ruleId, priority, from, to).plan(plan).value(value).build();
    }

    /** Readable factory over the 18-arg {@link RuleRow} constructor (house rule R6). */
    private static final class RuleRowBuilder {
        private final String ruleId;
        private final int priority;
        private final int from;
        private final int to;
        private BigDecimal annualRate;
        private PrincipalPlanKind plan = PrincipalPlanKind.INHERIT;
        private BigDecimal principalValue;
        private String principalSequence;
        private InterestTreatmentKind treatment = InterestTreatmentKind.PAY_AS_INCURRED;
        private SpecialActionKind action = SpecialActionKind.NONE;
        private String condition;

        private RuleRowBuilder(String ruleId, int priority, int from, int to) {
            this.ruleId = ruleId;
            this.priority = priority;
            this.from = from;
            this.to = to;
        }

        RuleRowBuilder rate(String annualRate) {
            this.annualRate = new BigDecimal(annualRate);
            return this;
        }

        RuleRowBuilder plan(PrincipalPlanKind plan) {
            this.plan = plan;
            return this;
        }

        RuleRowBuilder value(String principalValue) {
            this.principalValue = new BigDecimal(principalValue);
            return this;
        }

        RuleRowBuilder sequence(String principalSequence) {
            this.principalSequence = principalSequence;
            return this;
        }

        RuleRowBuilder treatment(InterestTreatmentKind treatment) {
            this.treatment = treatment;
            return this;
        }

        RuleRowBuilder action(SpecialActionKind action) {
            this.action = action;
            return this;
        }

        RuleRowBuilder condition(String condition) {
            this.condition = condition;
            return this;
        }

        RuleRow build() {
            return new RuleRow(ruleId, priority, from, to, annualRate, null, plan,
                    principalValue, principalSequence, treatment,
                    PaymentTiming.IN_ARREARS, action, null, 0, condition, null, 0, null);
        }
    }
}
