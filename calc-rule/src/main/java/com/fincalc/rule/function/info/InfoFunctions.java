package com.fincalc.rule.function.info;

import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.FormulaError;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.expression.ValueType;
import com.fincalc.rule.function.FormulaFunction;
import com.fincalc.rule.function.FunctionRegistry;
import java.util.List;

/**
 * The v1 information family (design §8.5): 5 Excel-compatible type predicates. They classify
 * <em>without coercion</em> — {@code ISNUMBER("1")} is FALSE, exactly like Excel — and, being
 * predicates, they never propagate an error: {@code ISERROR(1/0)} is TRUE, which is the whole
 * point of the family. {@code NA()} produces the {@code #N/A} error value.
 *
 * <p>Every function is stateless and pure.
 */
public final class InfoFunctions {

    private InfoFunctions() {
    }

    /** Registers the 5 information functions. */
    public static FunctionRegistry register(FunctionRegistry registry) {
        return registry
                .register(new IsBlank())
                .register(new IsNumber())
                .register(new IsText())
                .register(new IsError())
                .register(new Na());
    }

    private abstract static class Fn implements FormulaFunction {

        private final String code;

        Fn(String code) {
            this.code = code;
        }

        @Override
        public final String code() {
            return code;
        }

        @Override
        public final int minArity() {
            return 1;
        }

        @Override
        public final int maxArity() {
            return 1;
        }

        @Override
        public final Value invoke(List<Value> args, EvalContext context) {
            return Value.bool(decide(args.get(0)));
        }

        abstract boolean decide(Value arg);
    }

    private static class IsBlank extends Fn {
        IsBlank() {
            super("ISBLANK");
        }

        @Override
        boolean decide(Value arg) {
            return arg.type() == ValueType.BLANK;
        }
    }

    private static class IsNumber extends Fn {
        IsNumber() {
            super("ISNUMBER");
        }

        @Override
        boolean decide(Value arg) {
            return arg.type() == ValueType.NUMBER;
        }
    }

    private static class IsText extends Fn {
        IsText() {
            super("ISTEXT");
        }

        @Override
        boolean decide(Value arg) {
            return arg.type() == ValueType.TEXT;
        }
    }

    private static class IsError extends Fn {
        IsError() {
            super("ISERROR");
        }

        @Override
        boolean decide(Value arg) {
            return arg.type() == ValueType.ERROR;
        }
    }

    /** {@code NA()} — the {@code #N/A} error value itself. */
    private static class Na implements FormulaFunction {
        @Override
        public String code() {
            return "NA";
        }

        @Override
        public int minArity() {
            return 0;
        }

        @Override
        public int maxArity() {
            return 0;
        }

        @Override
        public Value invoke(List<Value> args, EvalContext context) {
            return Value.error(FormulaError.NA_ERROR);
        }
    }
}
