package com.fincalc.rule.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincalc.common.error.BusinessException;
import com.fincalc.domain.model.InterestTreatmentKind;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.PrincipalPlanKind;
import com.fincalc.domain.model.RuleOutcome;
import com.fincalc.domain.model.SpecialActionKind;
import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.function.FunctionRegistry;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompiledRuleSetTest {

    @Test
    void resolve_returns_lowest_priority_matching_rule() {
        CompiledRuleSet set = ruleSet(
                row("R1", 20, 1, 6, "0.06"),
                row("R2", 10, 1, 3, "0.03"));
        RuleOutcome out = set.resolve(2, new BigDecimal("1000000"), 30, new BigDecimal("0.06"));
        assertThat(out.ruleId()).isEqualTo("R2");
        assertThat(out.annualRate()).isEqualByComparingTo("0.03");
    }

    @Test
    void unmatched_period_returns_not_matched() {
        CompiledRuleSet set = ruleSet(row("R1", 10, 1, 3, "0.06"));
        assertThat(set.resolve(5, BigDecimal.ONE, 30, new BigDecimal("0.06")).matched()).isFalse();
    }

    @Test
    void hash_is_stable_for_same_rows() {
        CompiledRuleSet a = ruleSet(row("R1", 10, 1, 12, "0.06"));
        CompiledRuleSet b = ruleSet(row("R1", 10, 1, 12, "0.06"));
        assertThat(a.ruleSetHash()).isEqualTo(b.ruleSetHash()).hasSize(64);
    }

    @Test
    void rejects_invalid_row_range() {
        assertThatThrownBy(() -> row("R1", 10, 0, 6, "0.06"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-2001")
                .hasMessageContaining("fromPeriod >= 1");
        assertThatThrownBy(() -> row("R1", 10, 7, 3, "0.06"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-2001");
    }

    @Test
    void withCondition_combines_range_and_condition() {
        CompiledRule base = new CompiledRule(row("R1", 10, 1, 12, "0.06"));
        CompiledRule conditioned = base.withCondition(
                ctx -> ctx.variable("PERIOD").asNumber().intValue() <= 6);
        EvalContext early = new EvalContext(
                Map.of("PERIOD", Value.number(BigDecimal.valueOf(3))), FunctionRegistry.empty());
        EvalContext late = new EvalContext(
                Map.of("PERIOD", Value.number(BigDecimal.valueOf(7))), FunctionRegistry.empty());
        assertThat(base.matches(7)).isTrue();
        assertThat(conditioned.matches(7, late)).isFalse();
        assertThat(conditioned.matches(7, early)).isTrue();
        assertThat(conditioned.matches(13, early)).isFalse();
        assertThat(conditioned.matches(3, late)).isFalse();
    }

    private static RuleRow row(String ruleId, int priority, int fromPeriod, int toPeriod,
            String annualRate) {
        return new RuleRow(ruleId, priority, fromPeriod, toPeriod, new BigDecimal(annualRate),
                null, PrincipalPlanKind.INHERIT, null, null,
                InterestTreatmentKind.PAY_AS_INCURRED, PaymentTiming.IN_ARREARS,
                SpecialActionKind.NONE, null, 0, null, null, 0, null);
    }

    private static CompiledRuleSet ruleSet(RuleRow... rows) {
        return new CompiledRuleSet(Arrays.asList(rows));
    }
}
