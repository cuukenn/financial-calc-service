package com.fincalc.rule.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincalc.common.error.BusinessException;
import com.fincalc.domain.model.GracePeriod;
import com.fincalc.domain.model.InterestTreatmentKind;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PaymentFrequency;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.PrincipalPlanKind;
import com.fincalc.domain.model.RateChangePolicy;
import com.fincalc.domain.model.RoundingPolicy;
import com.fincalc.domain.model.SpecialActionKind;
import com.fincalc.domain.model.ResidualHandling;
import com.fincalc.domain.model.RepaymentMethodId;
import com.fincalc.rule.compiler.RuleCompiler;
import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.ExpressionParser;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.function.FunctionRegistry;
import com.fincalc.rule.model.CompiledRuleSet;
import com.fincalc.rule.model.RuleRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of the external function script pipeline (Task 27): {@code .fcs} parsing, loading from
 * files and classpath resources, composition with forward references, recursion/override
 * rejection, file:line syntax errors, rule-expression integration and the definition-aware
 * registry hash. JDK 11: the brief's text blocks are rendered as string concatenation with
 * identical content.
 */
class FunctionScriptLoaderTest {

    @TempDir
    Path tempDir;

    // ---- Brief Step 1 tests ----

    @Test
    void defines_and_calls_simple_function() {
        FunctionRegistry reg = load("VERSION 1\n"
                + "DEFINE 含税金额(AMOUNT, VAT_RATE) = ROUND(AMOUNT * (1 + VAT_RATE), 2)\n");
        assertThat(eval("含税金额(100, 0.06)", reg).asNumber()).isEqualByComparingTo("106.00");
    }

    @Test
    void supports_forward_reference_and_composition() {
        // Disclosed fix: the brief asserts 94.34 (= 100 / 1.06) while its own script body
        // 含税金额(RENT, -VAT_RATE) computes ROUND(100 * (1 - 0.06), 2) = 94.00; the two cannot
        // both hold. Per ruling, the script line stays verbatim and the assertion follows the
        // script's actual arithmetic.
        FunctionRegistry reg = load("VERSION 1\n"
                + "DEFINE 租金净额(RENT, VAT_RATE) = 含税金额(RENT, -VAT_RATE)\n"
                + "DEFINE 含税金额(AMOUNT, VAT_RATE) = ROUND(AMOUNT * (1 + VAT_RATE), 2)\n");
        assertThat(eval("租金净额(100, 0.06)", reg).asNumber()).isEqualByComparingTo("94.00");
    }

    @Test
    void rejects_recursive_definition() {
        assertThatThrownBy(() -> load("VERSION 1\n"
                + "DEFINE A(X) = B(X)\n"
                + "DEFINE B(X) = A(X)\n"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3011");
    }

    @Test
    void rejects_override_of_builtin_without_keyword() {
        assertThatThrownBy(() -> load("VERSION 1\n"
                + "DEFINE ROUND(X, N) = X\n"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3010");
    }

    @Test
    void syntax_error_reports_file_and_line() {
        assertThatThrownBy(() -> load("VERSION 1\n"
                + "DEFINE A(X) = X +\n"))
                .hasMessageContaining("test.fcs").hasMessageContaining("2");
    }

    @Test
    void script_can_be_used_inside_rule_expression() {
        FunctionRegistry reg = load("VERSION 1\n"
                + "DEFINE 高利率(R) = R >= 0.08\n");
        RuleCompiler compiler = new RuleCompiler(reg);
        CompiledRuleSet set = compiler.compile(
                List.of(rateRowWithCondition("R1", 1, 12, "0.06", "高利率(RATE)")),
                element(12), false);
        // Context-less resolve skips conditioned rules (Task 26 semantics), so R1 never matches.
        assertThat(set.resolve(1, BigDecimal.ONE, 30, new BigDecimal("0.09")).matched()).isFalse();
    }

    @Test
    void hash_changes_when_script_changes() {
        FunctionRegistry a = load("VERSION 1\nDEFINE F(X) = X + 1\n");
        FunctionRegistry b = load("VERSION 1\nDEFINE F(X) = X + 2\n");
        assertThat(a.hash()).isNotEqualTo(b.hash());
    }

    // ---- Expansion node budget (R63) ----

    @Test
    void rejects_exponential_expansion_with_node_budget() {
        // 40 chained-doubler definitions: F39(X) = X, Fn(X) = Fn+1(X) + Fn+1(X). Full expansion
        // of F0 would materialize > 2^40 nodes — far beyond any heap — so the load must abort
        // with FCS-RULE-3012 and must do so FAST: if the budget is not enforced, this test
        // hangs/OOMs instead of failing.
        StringBuilder script = new StringBuilder("VERSION 1\n");
        int chain = 40;
        for (int i = 0; i < chain; i++) {
            script.append("DEFINE F").append(i).append("(X) = ");
            if (i == chain - 1) {
                script.append("X\n");
            } else {
                script.append("F").append(i + 1).append("(X) + F").append(i + 1).append("(X)\n");
            }
        }
        long start = System.nanoTime();
        assertThatThrownBy(() -> load(script.toString()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3012");
        assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(10));
    }

    // ---- UTF-8 BOM tolerance (final-review fix wave) ----

    @Test
    void bom_prefixed_script_loads() {
        // A script saved by a Windows editor commonly opens with a UTF-8 BOM; the loader must
        // strip it before the VERSION check instead of failing with a syntax error.
        FunctionRegistry reg = load("\uFEFFVERSION 1\nDEFINE F(X) = X + 1\n");
        assertThat(eval("F(1)", reg).asNumber()).isEqualByComparingTo("2");
    }

    // ---- Classpath loading (Task 29 startup wiring) ----

    @Test
    void classpath_scripts_load_and_compose() {
        FunctionRegistry reg = FunctionScriptLoader.loadClasspath("functions",
                FunctionRegistry.defaults());
        assertThat(eval("含税金额(100, 0.06)", reg).asNumber()).isEqualByComparingTo("106.00");
        // rent.fcs references commons.fcs' 含税金额: 94.00 / 1.06 → 88.68.
        assertThat(eval("租金净额(100, 0.06)", reg).asNumber()).isEqualByComparingTo("88.68");
        // override.fcs replaces the ABS built-in via the OVERRIDE keyword.
        assertThat(eval("ABS(-5)", reg).asNumber()).isEqualByComparingTo("-5");
    }

    @Test
    void classpath_later_resource_overrides_same_name() {
        FunctionRegistry reg = FunctionScriptLoader.loadClasspath("functions",
                FunctionRegistry.defaults());
        // a_first.fcs defines F(X) = X + 1, z_last.fcs redefines F(X) = X + 2; resources load in
        // sorted name order and the later one wins.
        assertThat(eval("F(1)", reg).asNumber()).isEqualByComparingTo("3");
    }

    @Test
    void classpath_missing_prefix_returns_base_registry() {
        FunctionRegistry base = FunctionRegistry.defaults();
        assertThat(FunctionScriptLoader.loadClasspath("no/such/prefix", base)).isSameAs(base);
    }

    // ---- Helpers (R6: per-class fixtures) ----

    /** Writes the script to {@code test.fcs} in a temp dir so file:line errors carry the name. */
    private FunctionRegistry load(String text) {
        try {
            Path file = tempDir.resolve("test.fcs");
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
            return FunctionScriptLoader.load(List.of(file), FunctionRegistry.defaults());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Value eval(String expression, FunctionRegistry registry) {
        return ExpressionParser.parse(expression, registry)
                .evaluate(new EvalContext(Map.of(), registry));
    }

    private static RuleRow rateRowWithCondition(String ruleId, int from, int to,
            String rate, String condition) {
        return builder(ruleId, from, to).rate(rate).condition(condition).build();
    }

    private static RuleRowBuilder builder(String ruleId, int from, int to) {
        return new RuleRowBuilder(ruleId, from, to);
    }

    private static LeaseElement element(int periods) {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000"))
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(LocalDate.of(2026, 1, 1))
                .firstRentDate(LocalDate.of(2026, 2, 1))
                .periods(periods)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualHandling(ResidualHandling.SEPARATE)
                .method(RepaymentMethodId.RULE_DRIVEN)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
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
