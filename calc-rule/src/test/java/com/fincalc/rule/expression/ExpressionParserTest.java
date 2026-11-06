package com.fincalc.rule.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincalc.common.error.BusinessException;
import com.fincalc.rule.function.FormulaFunction;
import com.fincalc.rule.function.FunctionRegistry;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpressionParserTest {

    @Test
    void evaluates_arithmetic_with_precedence() {
        assertThat(eval("1 + 2 * 3").asNumber()).isEqualByComparingTo("7");
        assertThat(eval("2 ^ 3 + 1").asNumber()).isEqualByComparingTo("9");
    }

    @Test
    void evaluates_comparison_and_logic() {
        Expression e = parse("PERIOD >= 1 && PERIOD <= 12 && RATE > 0.05");
        assertThat(e.test(ctx(3))).isTrue();
        assertThat(e.test(ctx(13))).isFalse();
    }

    @Test
    void function_call_resolves_from_registry() {
        FunctionRegistry reg = FunctionRegistry.empty().register(new FormulaFunction() {
            public String code() { return "DOUBLE"; }
            public int minArity() { return 1; }
            public int maxArity() { return 1; }
            public Value invoke(List<Value> args, EvalContext c) {
                return Value.number(args.get(0).asNumber().multiply(new BigDecimal("2")));
            }
        });
        assertThat(eval("DOUBLE(21)", reg).asNumber()).isEqualByComparingTo("42");
    }

    @Test
    void division_by_zero_becomes_excel_error_value() {
        Value v = eval("1 / 0");
        assertThat(v.type()).isEqualTo(ValueType.ERROR);
        assertThat(((FormulaError) v.raw()).code()).isEqualTo("FCS-RULE-3005");
    }

    @Test
    void rejects_unknown_variable() {
        assertThatThrownBy(() -> parse("FOO > 1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3001");
    }

    @Test
    void rejects_unknown_function() {
        assertThatThrownBy(() -> parse("NOPE(1)"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3009");
    }

    @Test
    void rejects_method_call_and_property_access() {
        assertThatThrownBy(() -> parse("PERIOD.getClass()"))
                .isInstanceOf(BusinessException.class);
    }

    private static Value eval(String expr) {
        return eval(expr, FunctionRegistry.empty());
    }

    private static Value eval(String expr, FunctionRegistry functions) {
        return parse(expr, functions).evaluate(new EvalContext(Map.of(), functions));
    }

    private static Expression parse(String expr) {
        return parse(expr, FunctionRegistry.empty());
    }

    private static Expression parse(String expr, FunctionRegistry functions) {
        return ExpressionParser.parse(expr, functions);
    }

    private static EvalContext ctx(int period) {
        Map<String, Value> variables = new LinkedHashMap<>();
        variables.put("PERIOD", Value.number(BigDecimal.valueOf(period)));
        variables.put("RATE", Value.number(new BigDecimal("0.08")));
        return new EvalContext(variables, FunctionRegistry.empty());
    }
}
