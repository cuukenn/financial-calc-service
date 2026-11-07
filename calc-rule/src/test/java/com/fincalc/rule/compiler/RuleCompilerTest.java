package com.fincalc.rule.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fincalc.domain.model.GracePeriod;
import com.fincalc.domain.model.InterestTreatmentKind;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PaymentFrequency;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.PrincipalPlanKind;
import com.fincalc.domain.model.RateChangePolicy;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.model.SpecialActionKind;
import com.fincalc.domain.model.ValidationIssue;
import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.function.FunctionRegistry;
import com.fincalc.rule.model.CompiledRuleSet;
import com.fincalc.rule.model.RuleRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests of the rule compiler (Task 26): V1–V8 validation collected aggregate-then-throw, compile
 * time condition parsing, and the hash/resolve semantics the compiler introduces on
 * {@link CompiledRuleSet}. The {@code compiler()} helper is the shared fixture later tasks
 * (28/29) reuse per the brief.
 */
class RuleCompilerTest {

    private static final BigDecimal PRINCIPAL = new BigDecimal("1000000");

    // ---- Brief Step 1 tests, verbatim ----

    @Test
    void rejects_gap_in_coverage() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(row("R1", 1, 6)), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2001"));
    }

    @Test
    void allows_gap_when_partial_enabled() {
        CompiledRuleSet set = compiler().compile(List.of(row("R1", 1, 6)), element(12), true);
        assertThat(set.resolve(8, BigDecimal.ONE, 30, new BigDecimal("0.06")).matched()).isFalse();
    }

    @Test
    void rejects_percent_sum_not_100() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(percentRow("R1", 1, 12, "60")), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2003"));
    }

    @Test
    void rejects_unsupported_special_action() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(rowWithAction("PREPAY")), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2005"));
    }

    @Test
    void rejects_overlapping_same_priority() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(row("R1", 1, 6), row("R2", 3, 9)), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2002"));
    }

    // ---- V1: row range against the element ----

    @Test
    void rejects_row_reaching_beyond_element_periods() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(row("R1", 1, 18)), element(12), false));
        assertThat(ex.issues()).anySatisfy(i ->
                assertThat(i.code()).isEqualTo("FCS-RULE-2001"));
    }

    // ---- V4/V5/V6: principal plan totals ----

    @Test
    void rejects_percent_sum_over_100() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(
                        List.of(percentRow("R1", 1, 6, "70"), percentRow("R2", 7, 12, "50")),
                        element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2003"));
    }

    @Test
    void accepts_percent_sum_exactly_100() {
        CompiledRuleSet set = compiler().compile(
                List.of(percentRow("R1", 1, 6, "60"), percentRow("R2", 7, 12, "40")),
                element(12), false);
        assertThat(set.rules()).hasSize(2);
    }

    @Test
    void rejects_fixed_total_mismatch() {
        // FIXED 120000 x 12 = 1,440,000 != 1,000,000, and no algorithm-driven row hides it.
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(fixedRow("R1", 1, 12, "120000")), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2003"));
    }

    @Test
    void accepts_fixed_total_matching_principal() {
        CompiledRuleSet set = compiler().compile(
                List.of(fixedRow("R1", 1, 10, "100000"),
                        fixedRow("R2", 11, 12, "0")),
                element(12), false);
        assertThat(set.rules()).hasSize(2);
    }

    @Test
    void skips_fixed_total_check_when_algorithm_driven_rows_present() {
        // An INHERIT row makes the deterministic total incomplete, so V5 stays silent.
        CompiledRuleSet set = compiler().compile(
                List.of(fixedRow("R1", 1, 6, "120000"), row("R2", 7, 12)),
                element(12), false);
        assertThat(set.rules()).hasSize(2);
    }

    @Test
    void rejects_custom_sequence_length_mismatch() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(
                        List.of(customRow("R1", 1, 6, "200000,200000,200000,200000,200000,200000,200000")),
                        element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2003"));
    }

    @Test
    void rejects_blank_custom_sequence() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(customRow("R1", 1, 6, " ")), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2003"));
    }

    @Test
    void accepts_custom_sequence_matching_covered_periods() {
        CompiledRuleSet set = compiler().compile(
                List.of(customRow("R1", 1, 6, "200000,200000,200000,200000,100000,100000")),
                element(6), false);
        assertThat(set.rules()).hasSize(1);
    }

    // ---- V7/V8 ----

    @Test
    void rejects_annual_rate_out_of_range() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(rateRow("R1", 1, 12, "1.2")), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2006"));
    }

    @Test
    void rejects_negative_annual_rate() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(rateRow("R1", 1, 12, "-0.01")), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2006"));
    }

    @Test
    void accepts_boundary_annual_rates() {
        CompiledRuleSet set = compiler().compile(
                List.of(rateRow("R1", 1, 6, "0"), rateRow("R2", 7, 12, "1")), element(12), false);
        assertThat(set.rules()).hasSize(2);
    }

    @Test
    void rejects_extend_action() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(rowWithAction("EXTEND")), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2005"));
    }

    // ---- Aggregate-then-throw ----

    @Test
    void collects_all_issues_before_throwing_once() {
        // One row, three findings: rate 1.5 (V7), reserved action (V8), gap 7..12 (V3).
        RuleRow bad = builder("R1", 1, 6).rate("1.5").action(SpecialActionKind.PREPAY).build();
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(bad), element(12), false));
        assertThat(ex.issues()).extracting(ValidationIssue::code)
                .contains("FCS-RULE-2006", "FCS-RULE-2005", "FCS-RULE-2001");
    }

    // ---- Duplicate ruleId (carry-forward 3) ----

    @Test
    void rejects_duplicate_rule_ids() {
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(row("R1", 1, 6), row("R1", 7, 12)), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-2002"));
    }

    // ---- Compile-time condition parsing (brief Step 3: 未知函数在编译期就报 3009) ----

    @Test
    void rejects_unknown_function_in_condition_at_compile_time() {
        RuleRow conditioned = builder("R1", 1, 12).condition("NOPE(1)").build();
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(conditioned), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-3009"));
    }

    @Test
    void rejects_unknown_variable_in_condition_at_compile_time() {
        RuleRow conditioned = builder("R1", 1, 12).condition("FOO > 1").build();
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(conditioned), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-3001"));
    }

    @Test
    void rejects_syntax_error_in_condition_at_compile_time() {
        RuleRow conditioned = builder("R1", 1, 12).condition("PERIOD >").build();
        RuleValidationException ex = assertThrows(RuleValidationException.class,
                () -> compiler().compile(List.of(conditioned), element(12), false));
        assertThat(ex.issues()).anySatisfy(i -> assertThat(i.code()).isEqualTo("FCS-RULE-3004"));
    }

    // ---- Condition semantics: attached rule, context resolve, context-less resolve ----

    @Test
    void attaches_parsed_condition_and_resolves_with_context() {
        RuleRow conditioned = builder("R1", 1, 12).condition("PERIOD <= 6").build();
        CompiledRuleSet set = compiler().compile(List.of(conditioned), element(12), true);
        assertThat(set.rules()).hasSize(1);
        assertThat(set.rules().get(0).condition()).isNotNull();

        EvalContext early = context(3);
        EvalContext late = context(9);
        assertThat(set.resolve(3, PRINCIPAL, 30, new BigDecimal("0.06"), early).ruleId())
                .isEqualTo("R1");
        assertThat(set.resolve(9, PRINCIPAL, 30, new BigDecimal("0.06"), late).matched()).isFalse();
        assertThat(set.resolve(13, PRINCIPAL, 30, new BigDecimal("0.06"), early).matched()).isFalse();
    }

    @Test
    void context_less_resolve_never_matches_conditioned_rule() {
        // Honest semantics (documented on CompiledRuleSet.resolve): the 4-arg resolve carries no
        // EvalContext, so a conditioned rule must NOT silently match on its range alone.
        RuleRow conditioned = builder("R1", 1, 12).condition("PERIOD <= 6").build();
        CompiledRuleSet set = compiler().compile(List.of(conditioned), element(12), false);
        assertThat(set.resolve(3, PRINCIPAL, 30, new BigDecimal("0.06")).matched()).isFalse();
    }

    // ---- Hash: registry digest and condition source folded in (carry-forwards 1/2) ----

    @Test
    void function_registry_hash_exposes_injected_registry_digest() {
        CompiledRuleSet set = compiler().compile(List.of(row("R1", 1, 12)), element(12), false);
        assertThat(set.functionRegistryHash())
                .isEqualTo(FunctionRegistry.defaults().hash())
                .hasSize(64);
    }

    @Test
    void hash_distinguishes_sets_differing_only_in_condition() {
        RuleRow plain = builder("R1", 1, 12).build();
        RuleRow conditioned = builder("R1", 1, 12).condition("PERIOD <= 6").build();
        CompiledRuleSet a = compiler().compile(List.of(plain), element(12), false);
        CompiledRuleSet b = compiler().compile(List.of(conditioned), element(12), false);
        assertThat(a.ruleSetHash()).isNotEqualTo(b.ruleSetHash()).hasSize(64);
    }

    @Test
    void hash_is_stable_and_ignores_pipes_in_free_text() {
        // Carry-forward 2: '|' in free-text fields is escaped in the canonical form, so it cannot
        // shift field boundaries and two such sets still hash stably.
        RuleRow a = builder("R|1", 1, 6).build();
        RuleRow b = builder("R|1", 1, 6).build();
        CompiledRuleSet setA = compiler().compile(List.of(a), element(12), true);
        CompiledRuleSet setB = compiler().compile(List.of(b), element(12), true);
        assertThat(setA.ruleSetHash()).isEqualTo(setB.ruleSetHash()).hasSize(64);
    }

    // ---- Resolved outcome sanity for a compiled set ----

    @Test
    void resolve_picks_lowest_priority_row_after_compile() {
        CompiledRuleSet set = compiler().compile(
                List.of(row("R1", 1, 12), builder("R2", 1, 3).priority(5).build()),
                element(12), false);
        assertThat(set.resolve(2, PRINCIPAL, 30, new BigDecimal("0.06")).ruleId()).isEqualTo("R2");
        assertThat(set.resolve(8, PRINCIPAL, 30, new BigDecimal("0.06")).ruleId()).isEqualTo("R1");
    }

    // ---- Helpers (R6: per-class fixtures; R57/factories instead of the 18-arg ctor) ----

    private static RuleCompiler compiler() {
        return new RuleCompiler(FunctionRegistry.defaults());
    }

    private static LeaseElement element(int periods) {
        return LeaseElement.builder()
                .principal(PRINCIPAL)
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(LocalDate.of(2026, 1, 1))
                .firstRentDate(LocalDate.of(2026, 2, 1))
                .periods(periods)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualHandling(com.fincalc.domain.model.ResidualHandling.SEPARATE)
                .method(com.fincalc.domain.model.RepaymentMethodId.RULE_DRIVEN)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }

    private static RuleRowBuilder builder(String ruleId, int from, int to) {
        return new RuleRowBuilder(ruleId, from, to);
    }

    private static RuleRow row(String ruleId, int from, int to) {
        return builder(ruleId, from, to).build();
    }

    private static RuleRow percentRow(String ruleId, int from, int to, String percentValue) {
        return builder(ruleId, from, to).plan(PrincipalPlanKind.PERCENT).value(percentValue).build();
    }

    private static RuleRow fixedRow(String ruleId, int from, int to, String value) {
        return builder(ruleId, from, to).plan(PrincipalPlanKind.FIXED).value(value).build();
    }

    private static RuleRow customRow(String ruleId, int from, int to, String sequence) {
        return builder(ruleId, from, to).plan(PrincipalPlanKind.CUSTOM).sequence(sequence).build();
    }

    private static RuleRow rateRow(String ruleId, int from, int to, String rate) {
        return builder(ruleId, from, to).rate(rate).build();
    }

    private static RuleRow rowWithAction(String action) {
        return builder("R1", 1, 12).action(SpecialActionKind.valueOf(action)).build();
    }

    private static EvalContext context(int period) {
        return new EvalContext(Map.of("PERIOD", Value.number(BigDecimal.valueOf(period))),
                FunctionRegistry.defaults());
    }

    /** Readable factory over the 18-arg {@link RuleRow} constructor (house rule R6). */
    private static final class RuleRowBuilder {
        private final String ruleId;
        private final int from;
        private final int to;
        private int priority = 10;
        private BigDecimal annualRate;
        private PrincipalPlanKind plan = PrincipalPlanKind.INHERIT;
        private BigDecimal principalValue;
        private String principalSequence;
        private SpecialActionKind action = SpecialActionKind.NONE;
        private String condition;

        private RuleRowBuilder(String ruleId, int from, int to) {
            this.ruleId = ruleId;
            this.from = from;
            this.to = to;
        }

        RuleRowBuilder priority(int priority) {
            this.priority = priority;
            return this;
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
                    principalValue, principalSequence, InterestTreatmentKind.PAY_AS_INCURRED,
                    PaymentTiming.IN_ARREARS, action, null, 0, condition, null, 0, null);
        }
    }
}
