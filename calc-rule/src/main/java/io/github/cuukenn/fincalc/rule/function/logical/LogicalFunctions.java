package io.github.cuukenn.fincalc.rule.function.logical;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.rule.expression.AstNode;
import io.github.cuukenn.fincalc.rule.expression.EvalContext;
import io.github.cuukenn.fincalc.rule.expression.Expression;
import io.github.cuukenn.fincalc.rule.expression.FormulaError;
import io.github.cuukenn.fincalc.rule.expression.Value;
import io.github.cuukenn.fincalc.rule.function.FormulaFunction;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import io.github.cuukenn.fincalc.rule.function.LazyFunction;
import java.util.List;

/**
 * The v1 logical family (design §8.5): 11 Excel-compatible functions. {@code IF}, {@code IFS},
 * {@code SWITCH}, {@code IFERROR} and {@code IFNA} are {@link LazyFunction}s — their arguments
 * arrive as unevaluated {@link Expression} nodes and only the selected branch is ever evaluated,
 * so an error in an untaken branch (a division by zero, an unresolvable call) stays dormant. The
 * rest are eager and coerce through {@link Value#asBoolean()} with Excel's readings (a non-zero
 * number is true, the texts {@code TRUE}/{@code FALSE} parse).
 *
 * <p>Text equality inside {@code SWITCH} is case-insensitive (ruling R59, matching the
 * expression engine's {@code =} operator).
 *
 * <p>Every function is stateless and pure; failures surface as {@code ERROR} values.
 */
public final class LogicalFunctions {

    private LogicalFunctions() {
    }

    /** Registers the 11 logical functions. */
    public static FunctionRegistry register(FunctionRegistry registry) {
        return registry
                .register(new If())
                .register(new Ifs())
                .register(new And())
                .register(new Or())
                .register(new Not())
                .register(new Xor())
                .register(new IfError())
                .register(new IfNa())
                .register(new Switch())
                .register(new True())
                .register(new False());
    }

    /**
     * Base for the family: eager template converting coercion failures into {@code ERROR} values,
     * with a defensive eager path for lazy functions (only reachable if a caller invokes them
     * outside the AST's lazy dispatch).
     */
    private abstract static class Fn implements FormulaFunction {

        private final String code;
        private final int minArity;
        private final int maxArity;

        Fn(String code, int minArity, int maxArity) {
            this.code = code;
            this.minArity = minArity;
            this.maxArity = maxArity;
        }

        @Override
        public final String code() {
            return code;
        }

        @Override
        public final int minArity() {
            return minArity;
        }

        @Override
        public final int maxArity() {
            return maxArity;
        }

        @Override
        public Value invoke(List<Value> args, EvalContext context) {
            try {
                return apply(args);
            } catch (BusinessException ex) {
                return AstNode.errorFrom(ex);
            } catch (ArithmeticException ex) {
                return Value.error(FormulaError.NUM_ERROR);
            }
        }

        abstract Value apply(List<Value> args);
    }

    /** Base for lazy functions: laziness through {@link #applyLazy}, eager path kept defensive. */
    private abstract static class LazyFn extends Fn implements LazyFunction {

        LazyFn(String code, int minArity, int maxArity) {
            super(code, minArity, maxArity);
        }

        @Override
        public final Value invoke(List<Value> args, EvalContext context) {
            // Defensive eager path; the AST dispatches lazy functions to invokeLazy.
            return super.invoke(args, context);
        }

        @Override
        public final Value invokeLazy(List<Expression> args, EvalContext context) {
            try {
                return applyLazy(args, context);
            } catch (BusinessException ex) {
                return AstNode.errorFrom(ex);
            } catch (ArithmeticException ex) {
                return Value.error(FormulaError.NUM_ERROR);
            }
        }

        abstract Value applyLazy(List<Expression> args, EvalContext context);
    }

    /** Evaluates one lazy argument, converting coercion failures into {@code ERROR} values. */
    private static Value evalOf(Expression arg, EvalContext context) {
        try {
            return arg.evaluate(context);
        } catch (BusinessException ex) {
            return AstNode.errorFrom(ex);
        } catch (ArithmeticException ex) {
            return Value.error(FormulaError.NUM_ERROR);
        }
    }

    /** Reads a value as Excel's condition truth; an {@code ERROR} value flows through. */
    private static Value boolOf(Value value) {
        try {
            return Value.bool(value.asBoolean());
        } catch (BusinessException ex) {
            return AstNode.errorFrom(ex);
        }
    }

    private static boolean isError(Value value) {
        return value.type() == io.github.cuukenn.fincalc.rule.expression.ValueType.ERROR;
    }

    /** {@code IF(condition, then, [else])} — only the selected branch is evaluated. */
    private static class If extends LazyFn {
        If() {
            super("IF", 2, 3);
        }

        @Override
        Value applyLazy(List<Expression> args, EvalContext context) {
            Value condition = boolOf(evalOf(args.get(0), context));
            if (isError(condition)) {
                return condition;
            }
            if (condition.asBoolean()) {
                return evalOf(args.get(1), context);
            }
            // Excel's two-argument IF yields FALSE when the else branch is missing.
            return args.size() > 2 ? evalOf(args.get(2), context) : Value.bool(false);
        }

        @Override
        Value apply(List<Value> args) {
            Value condition = boolOf(args.get(0));
            if (isError(condition)) {
                return condition;
            }
            if (condition.asBoolean()) {
                return args.get(1);
            }
            return args.size() > 2 ? args.get(2) : Value.bool(false);
        }
    }

    /** {@code IFS(condition1, value1, condition2, value2, ...)} — first true pair wins. */
    private static class Ifs extends LazyFn {
        Ifs() {
            super("IFS", 2, -1);
        }

        @Override
        Value applyLazy(List<Expression> args, EvalContext context) {
            if (args.size() % 2 != 0) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            for (int i = 0; i < args.size(); i += 2) {
                Value condition = boolOf(evalOf(args.get(i), context));
                if (isError(condition)) {
                    return condition;
                }
                if (condition.asBoolean()) {
                    return evalOf(args.get(i + 1), context);
                }
            }
            // Excel: no matching condition is #N/A.
            return Value.error(FormulaError.NA_ERROR);
        }

        @Override
        Value apply(List<Value> args) {
            if (args.size() % 2 != 0) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            for (int i = 0; i < args.size(); i += 2) {
                Value condition = boolOf(args.get(i));
                if (isError(condition)) {
                    return condition;
                }
                if (condition.asBoolean()) {
                    return args.get(i + 1);
                }
            }
            return Value.error(FormulaError.NA_ERROR);
        }
    }

    private abstract static class AllBooleans extends Fn {
        AllBooleans(String code) {
            super(code, 1, -1);
        }

        @Override
        final Value apply(List<Value> args) {
            int trueCount = 0;
            for (Value arg : args) {
                Value bool = boolOf(arg);
                if (isError(bool)) {
                    return bool;
                }
                if (bool.asBoolean()) {
                    trueCount++;
                }
            }
            return Value.bool(decide(trueCount, args.size()));
        }

        abstract boolean decide(int trueCount, int total);
    }

    private static class And extends AllBooleans {
        And() {
            super("AND");
        }

        @Override
        boolean decide(int trueCount, int total) {
            return trueCount == total;
        }
    }

    private static class Or extends AllBooleans {
        Or() {
            super("OR");
        }

        @Override
        boolean decide(int trueCount, int total) {
            return trueCount > 0;
        }
    }

    private static class Xor extends AllBooleans {
        Xor() {
            super("XOR");
        }

        @Override
        boolean decide(int trueCount, int total) {
            return trueCount % 2 != 0;
        }
    }

    private static class Not extends Fn {
        Not() {
            super("NOT", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            Value bool = boolOf(args.get(0));
            return isError(bool) ? bool : Value.bool(!bool.asBoolean());
        }
    }

    /** {@code IFERROR(value, fallback)} — the fallback evaluates only on an error. */
    private static class IfError extends LazyFn {
        IfError() {
            super("IFERROR", 2, 2);
        }

        @Override
        Value applyLazy(List<Expression> args, EvalContext context) {
            Value value = evalOf(args.get(0), context);
            return isError(value) ? evalOf(args.get(1), context) : value;
        }

        @Override
        Value apply(List<Value> args) {
            return isError(args.get(0)) ? args.get(1) : args.get(0);
        }
    }

    /** {@code IFNA(value, fallback)} — like IFERROR but only for {@code #N/A}. */
    private static class IfNa extends LazyFn {
        IfNa() {
            super("IFNA", 2, 2);
        }

        @Override
        Value applyLazy(List<Expression> args, EvalContext context) {
            Value value = evalOf(args.get(0), context);
            boolean isNa = isError(value)
                    && ((FormulaError) value.raw()) == FormulaError.NA_ERROR;
            return isNa ? evalOf(args.get(1), context) : value;
        }

        @Override
        Value apply(List<Value> args) {
            boolean isNa = isError(args.get(0))
                    && ((FormulaError) args.get(0).raw()) == FormulaError.NA_ERROR;
            return isNa ? args.get(1) : args.get(0);
        }
    }

    /**
     * {@code SWITCH(expr, value1, result1, ..., [default])} — an odd number of arguments after
     * the switch expression means the last one is the default. Comparison is Excel equality:
     * case-insensitive for text, numeric otherwise.
     */
    private static class Switch extends LazyFn {
        Switch() {
            super("SWITCH", 3, -1);
        }

        @Override
        Value applyLazy(List<Expression> args, EvalContext context) {
            Value target = evalOf(args.get(0), context);
            int rest = args.size() - 1;
            int pairs = rest % 2 == 0 ? rest / 2 : (rest - 1) / 2;
            for (int i = 0; i < pairs; i++) {
                Value candidate = evalOf(args.get(1 + 2 * i), context);
                Value match = equalsOf(target, candidate);
                if (isError(match)) {
                    return match;
                }
                if (match.asBoolean()) {
                    return evalOf(args.get(2 + 2 * i), context);
                }
            }
            if (rest % 2 != 0) {
                return evalOf(args.get(args.size() - 1), context);
            }
            return Value.error(FormulaError.NA_ERROR);
        }

        @Override
        Value apply(List<Value> args) {
            throw new IllegalStateException("SWITCH is only reachable lazily");
        }
    }

    /** Excel equality over two values: case-insensitive text against text, numbers otherwise. */
    private static Value equalsOf(Value left, Value right) {
        try {
            boolean equal;
            if (left.type() == io.github.cuukenn.fincalc.rule.expression.ValueType.TEXT
                    && right.type() == io.github.cuukenn.fincalc.rule.expression.ValueType.TEXT) {
                equal = left.asText().equalsIgnoreCase(right.asText());
            } else {
                equal = left.asNumber().compareTo(right.asNumber()) == 0;
            }
            return Value.bool(equal);
        } catch (BusinessException ex) {
            return AstNode.errorFrom(ex);
        }
    }

    private static class True extends Fn {
        True() {
            super("TRUE", 0, 0);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.bool(true);
        }
    }

    private static class False extends Fn {
        False() {
            super("FALSE", 0, 0);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.bool(false);
        }
    }
}
