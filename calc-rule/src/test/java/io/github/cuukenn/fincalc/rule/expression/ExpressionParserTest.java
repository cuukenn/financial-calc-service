package io.github.cuukenn.fincalc.rule.expression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.rule.function.FormulaFunction;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
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

    @Test
    void rejects_nesting_beyond_the_depth_cap() {
        assertThatThrownBy(() -> parse(nestedParens(65)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3004")
                .hasMessageContaining("表达式嵌套过深")
                .hasMessageContaining("深度 65 > 64");
        assertThatThrownBy(() -> parse(nestedUnary(65)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-RULE-3004");
    }

    @Test
    void evaluates_nesting_at_the_depth_cap() {
        assertThat(eval(nestedParens(64)).asNumber()).isEqualByComparingTo("1");
        assertThat(eval(nestedUnary(64)).asNumber()).isEqualByComparingTo("1");
    }

    @Test
    void power_beyond_the_range_ceiling_is_num_error() {
        Value v = eval("2 ^ 2000");
        assertThat(v.type()).isEqualTo(ValueType.ERROR);
        assertThat(((FormulaError) v.raw()).code()).isEqualTo("FCS-RULE-3007");
        assertThat(eval("2 ^ 2000.5").type()).isEqualTo(ValueType.ERROR);
        assertThat(eval("2 ^ -2000").type()).isEqualTo(ValueType.ERROR);
    }

    @Test
    void power_within_the_range_ceiling_stays_numeric() {
        assertThat(eval("2 ^ 10").asNumber()).isEqualByComparingTo("1024");
        assertThat(eval("2 ^ 1000.5").type()).isEqualTo(ValueType.NUMBER);
        assertThat(eval("-2 ^ 3").asNumber()).isEqualByComparingTo("-8");
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

    private static String nestedParens(int depth) {
        StringBuilder expression = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            expression.append('(');
        }
        expression.append('1');
        for (int i = 0; i < depth; i++) {
            expression.append(')');
        }
        return expression.toString();
    }

    private static String nestedUnary(int depth) {
        StringBuilder expression = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            expression.append('-');
        }
        expression.append('1');
        return expression.toString();
    }
}
