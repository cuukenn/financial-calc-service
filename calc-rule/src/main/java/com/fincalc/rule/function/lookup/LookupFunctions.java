package com.fincalc.rule.function.lookup;

import com.fincalc.common.error.BusinessException;
import com.fincalc.rule.expression.AstNode;
import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.FormulaError;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.expression.ValueType;
import com.fincalc.rule.function.FunctionRegistry;
import java.math.BigDecimal;
import java.util.List;

/**
 * The v1 lookup family (design §8.5): 5 Excel-compatible functions. The DSL passes scalar
 * arguments only — there are no range or array values — so the vector-style Excel forms are
 * flattened into variadic argument lists (documented v1 deviations, one per function):
 *
 * <ul>
 *   <li>{@code CHOOSE(index, choice1, choice2, ...)} — Excel-exact.</li>
 *   <li>{@code INDEX(vector..., position)} — the last argument is the 1-based position, the
 *       preceding arguments are the vector.</li>
 *   <li>{@code MATCH(lookup, vector...)} — exact match only (Excel's match_type 0); Excel's
 *       approximate {@code 1}/{@code -1} modes are not expressible without a vector type.</li>
 *   <li>{@code LOOKUP(value, vector...)} — Excel's vector form with the result vector omitted:
 *       the largest vector entry not greater than the value, the vector being ascending.</li>
 *   <li>{@code VLOOKUP(lookup, key1, result1, key2, result2, ...)} — key/result pairs flattened;
 *       exact match ({@code range_lookup FALSE}), first key wins. Excel's two-range table form
 *       and approximate match are not expressible over scalar arguments.</li>
 * </ul>
 *
 * <p>Comparison is Excel equality: case-insensitive between two texts (ruling R59), numeric
 * otherwise (a text that looks like a number coerces, matching the engine's {@code =} operator).
 * A lookup that finds nothing is {@code #N/A}; a malformed call is {@code #VALUE!}.
 *
 * <p>Every function is stateless and pure; failures surface as {@code ERROR} values.
 */
public final class LookupFunctions {

    private LookupFunctions() {
    }

    /** Registers the 5 lookup functions. */
    public static FunctionRegistry register(FunctionRegistry registry) {
        return registry
                .register(new Choose())
                .register(new Index())
                .register(new Match())
                .register(new Lookup())
                .register(new Vlookup());
    }

    /** Template shared by every lookup function: coerce failures into {@code ERROR} values. */
    private abstract static class Fn implements com.fincalc.rule.function.FormulaFunction {

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
        public final Value invoke(List<Value> args, EvalContext context) {
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

    private static int toInt(BigDecimal value) {
        return value.setScale(0, java.math.RoundingMode.DOWN).intValueExact();
    }

    /**
     * Excel equality: case-insensitive text against text (R59), numeric otherwise. A mixed-type
     * pair whose text does not read as a number is simply not a match (Excel's {@code #N/A}
     * path), never a coercion error.
     */
    private static boolean equalTo(Value left, Value right) {
        if (left.type() == ValueType.TEXT && right.type() == ValueType.TEXT) {
            return left.asText().equalsIgnoreCase(right.asText());
        }
        try {
            return left.asNumber().compareTo(right.asNumber()) == 0;
        } catch (BusinessException ex) {
            return false;
        }
    }

    /** Excel's {@code <=} for {@code LOOKUP}; mixed-type coercion failures count as not-less. */
    private static boolean lessOrEqual(Value candidate, Value bound) {
        if (candidate.type() == ValueType.TEXT && bound.type() == ValueType.TEXT) {
            return candidate.asText().compareToIgnoreCase(bound.asText()) <= 0;
        }
        try {
            return candidate.asNumber().compareTo(bound.asNumber()) <= 0;
        } catch (BusinessException ex) {
            return false;
        }
    }

    private static class Choose extends Fn {
        Choose() {
            super("CHOOSE", 2, -1);
        }

        @Override
        Value apply(List<Value> args) {
            int index = toInt(args.get(0).asNumber());
            if (index < 1 || index > args.size() - 1) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            return args.get(index);
        }
    }

    private static class Index extends Fn {
        Index() {
            super("INDEX", 2, -1);
        }

        @Override
        Value apply(List<Value> args) {
            int position = toInt(args.get(args.size() - 1).asNumber());
            if (position < 1 || position > args.size() - 1) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            return args.get(position - 1);
        }
    }

    private static class Match extends Fn {
        Match() {
            super("MATCH", 2, -1);
        }

        @Override
        Value apply(List<Value> args) {
            Value target = args.get(0);
            for (int i = 1; i < args.size(); i++) {
                if (equalTo(target, args.get(i))) {
                    return Value.number(BigDecimal.valueOf(i));
                }
            }
            return Value.error(FormulaError.NA_ERROR);
        }
    }

    private static class Lookup extends Fn {
        Lookup() {
            super("LOOKUP", 2, -1);
        }

        @Override
        Value apply(List<Value> args) {
            Value target = args.get(0);
            Value best = null;
            for (int i = 1; i < args.size(); i++) {
                if (lessOrEqual(args.get(i), target)) {
                    best = args.get(i);
                }
            }
            // Scanning to the last qualifying entry matches Excel's answer on an ascending
            // vector and stays deterministic otherwise.
            return best == null ? Value.error(FormulaError.NA_ERROR) : best;
        }
    }

    private static class Vlookup extends Fn {
        Vlookup() {
            super("VLOOKUP", 3, -1);
        }

        @Override
        Value apply(List<Value> args) {
            int rest = args.size() - 1;
            if (rest % 2 != 0) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            Value target = args.get(0);
            for (int i = 0; i < rest; i += 2) {
                if (equalTo(target, args.get(1 + i))) {
                    return args.get(2 + i);
                }
            }
            return Value.error(FormulaError.NA_ERROR);
        }
    }
}
