package io.github.cuukenn.fincalc.rule.function.math;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.support.DecimalMath;
import io.github.cuukenn.fincalc.rule.expression.AstNode;
import io.github.cuukenn.fincalc.rule.expression.EvalContext;
import io.github.cuukenn.fincalc.rule.expression.FormulaError;
import io.github.cuukenn.fincalc.rule.expression.Value;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * The v1 math family (design §8.5): 21 Excel-compatible numeric functions, every computation on
 * {@code BigDecimal} — no {@code double}/{@code float} anywhere.
 *
 * <p>Excel rounding semantics pinned by test: {@code ROUND} is half-away-from-zero
 * ({@link RoundingMode#HALF_UP} on the magnitude), {@code INT} floors, {@code TRUNC}/{@code
 * ROUNDDOWN} truncate toward zero, {@code ROUNDUP} rounds away from zero, and {@code MOD}'s sign
 * follows its divisor via {@code n − d×floor(n/d)} computed exactly over scaled
 * {@code BigInteger}s — never Java's truncating {@code %}. The transcendental functions delegate
 * to {@link DecimalMath} and keep the expression engine's error mapping: out-of-domain arguments
 * and results beyond Excel's ~1e308 range yield {@code #NUM!} values, never thrown exceptions.
 *
 * <p>Every function is stateless and pure; failures surface as {@code ERROR} values through the
 * shared {@link Fn#invoke} template.
 */
public final class MathFunctions {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * Result-magnitude ceiling shared with {@code AstNode.BinaryNode}'s {@code ^} operator: Excel
     * maps results beyond ~1e308 (the double range) to {@code #NUM!}. 308·ln(10).
     */
    private static final BigDecimal POWER_RESULT_CEILING =
            DecimalMath.ln(BigDecimal.TEN).multiply(BigDecimal.valueOf(308), MC);

    private MathFunctions() {
    }

    /** Registers the 21 math functions. */
    public static FunctionRegistry register(FunctionRegistry registry) {
        return registry
                .register(new Abs())
                .register(new Round())
                .register(new RoundUp())
                .register(new RoundDown())
                .register(new Int())
                .register(new Trunc())
                .register(new Mod())
                .register(new Power())
                .register(new Sqrt())
                .register(new Exp())
                .register(new Ln())
                .register(new Log())
                .register(new Log10())
                .register(new Sign())
                .register(new Max())
                .register(new Min())
                .register(new Sum())
                .register(new Product())
                .register(new Average())
                .register(new Ceiling())
                .register(new Floor());
    }

    /** Template shared by every math function: coerce failures into {@code ERROR} values. */
    private abstract static class Fn implements io.github.cuukenn.fincalc.rule.function.FormulaFunction {

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

    /** Coerces one argument to a number; {@code BLANK} counts as 0, errors rethrow as data. */
    private static BigDecimal num(List<Value> args, int index) {
        return args.get(index).asNumber();
    }

    /** Excel truncates count-like arguments (digits, indices) toward zero. */
    private static int toInt(BigDecimal value) {
        return value.setScale(0, RoundingMode.DOWN).intValueExact();
    }

    private static class Abs extends Fn {
        Abs() {
            super("ABS", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(num(args, 0).abs());
        }
    }

    /** {@code ROUND(x, digits)} — half-away-from-zero on the digit scale. */
    private static class Round extends Fn {
        Round() {
            super("ROUND", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(num(args, 0).setScale(toInt(num(args, 1)), RoundingMode.HALF_UP));
        }
    }

    /** {@code ROUNDUP(x, digits)} — away from zero, like Excel. */
    private static class RoundUp extends Fn {
        RoundUp() {
            super("ROUNDUP", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(num(args, 0).setScale(toInt(num(args, 1)), RoundingMode.UP));
        }
    }

    /** {@code ROUNDDOWN(x, digits)} — toward zero, like Excel. */
    private static class RoundDown extends Fn {
        RoundDown() {
            super("ROUNDDOWN", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(num(args, 0).setScale(toInt(num(args, 1)), RoundingMode.DOWN));
        }
    }

    /** {@code INT(x)} — floor, so {@code INT(-1.5) = -2} like Excel. */
    private static class Int extends Fn {
        Int() {
            super("INT", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(num(args, 0).setScale(0, RoundingMode.FLOOR));
        }
    }

    /** {@code TRUNC(x, [digits])} — toward zero; digits defaults to 0. */
    private static class Trunc extends Fn {
        Trunc() {
            super("TRUNC", 1, 2);
        }

        @Override
        Value apply(List<Value> args) {
            int digits = args.size() > 1 ? toInt(num(args, 1)) : 0;
            return Value.number(num(args, 0).setScale(digits, RoundingMode.DOWN));
        }
    }

    /**
     * {@code MOD(n, d)} — Excel's sign-follows-divisor remainder, {@code n − d×floor(n/d)}.
     * Computed exactly over both values scaled to a common scale as {@code BigInteger}s, so
     * non-terminating quotients ({@code MOD(1, 3)}) stay exact and Java's truncating {@code %}
     * behaviour can never leak in.
     */
    private static class Mod extends Fn {
        Mod() {
            super("MOD", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal n = num(args, 0);
            BigDecimal d = num(args, 1);
            if (d.signum() == 0) {
                return Value.error(FormulaError.DIV_ZERO);
            }
            int scale = Math.max(n.scale(), d.scale());
            BigInteger numerator = n.setScale(scale).unscaledValue();
            BigInteger divisor = d.setScale(scale).unscaledValue();
            BigInteger remainder = numerator.remainder(divisor);
            if (remainder.signum() != 0 && remainder.signum() != divisor.signum()) {
                remainder = remainder.add(divisor);
            }
            return Value.number(new BigDecimal(remainder, scale));
        }
    }

    /**
     * {@code POWER(base, exponent)} — the same Excel readings as the engine's {@code ^}
     * operator ({@code 0^0 → #NUM!}, {@code 0^negative → #DIV/0!}, negative base with a
     * fractional exponent → {@code #NUM!}, results beyond ~1e308 → {@code #NUM!}).
     */
    private static class Power extends Fn {
        Power() {
            super("POWER", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            return power(num(args, 0), num(args, 1));
        }
    }

    private static Value power(BigDecimal base, BigDecimal exponent) {
        if (base.signum() == 0 && exponent.signum() == 0) {
            return Value.error(FormulaError.NUM_ERROR);
        }
        BigDecimal integral = exponent.stripTrailingZeros();
        if (integral.scale() <= 0) {
            if (base.signum() == 0 && integral.signum() < 0) {
                return Value.error(FormulaError.DIV_ZERO);
            }
            if (base.signum() != 0 && beyondCeiling(base.abs(), integral)) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(base.pow(integral.intValueExact(), MC));
        }
        if (base.signum() <= 0) {
            return Value.error(FormulaError.NUM_ERROR);
        }
        if (beyondCeiling(base, exponent)) {
            return Value.error(FormulaError.NUM_ERROR);
        }
        return Value.number(DecimalMath.pow(base, exponent));
    }

    /** |ln(base)·exponent| beyond 308·ln(10) is Excel's #NUM! range, as in {@code BinaryNode}. */
    private static boolean beyondCeiling(BigDecimal base, BigDecimal exponent) {
        return DecimalMath.ln(base).multiply(exponent).abs()
                .compareTo(POWER_RESULT_CEILING) > 0;
    }

    private static class Sqrt extends Fn {
        Sqrt() {
            super("SQRT", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal x = num(args, 0);
            if (x.signum() < 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(x.sqrt(MC));
        }
    }

    /** {@code EXP(x)} — results beyond ~1e308 map to #NUM! before they are computed. */
    private static class Exp extends Fn {
        Exp() {
            super("EXP", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal x = num(args, 0);
            if (x.abs().compareTo(POWER_RESULT_CEILING) > 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(DecimalMath.exp(x));
        }
    }

    private static class Ln extends Fn {
        Ln() {
            super("LN", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal x = num(args, 0);
            if (x.signum() <= 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(DecimalMath.ln(x));
        }
    }

    /** {@code LOG(x, [base])} — base 10 by default; {@code LOG(x, 1)} is Excel's #DIV/0!. */
    private static class Log extends Fn {
        Log() {
            super("LOG", 1, 2);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal x = num(args, 0);
            BigDecimal base = args.size() > 1 ? num(args, 1) : BigDecimal.TEN;
            if (x.signum() <= 0 || base.signum() <= 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            if (base.compareTo(BigDecimal.ONE) == 0) {
                return Value.error(FormulaError.DIV_ZERO);
            }
            return Value.number(DecimalMath.ln(x).divide(DecimalMath.ln(base), MC));
        }
    }

    private static class Log10 extends Fn {
        Log10() {
            super("LOG10", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal x = num(args, 0);
            if (x.signum() <= 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(DecimalMath.ln(x).divide(DecimalMath.ln(BigDecimal.TEN), MC));
        }
    }

    private static class Sign extends Fn {
        Sign() {
            super("SIGN", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(BigDecimal.valueOf(num(args, 0).signum()));
        }
    }

    private abstract static class Reducing extends Fn {
        Reducing(String code) {
            super(code, 1, -1);
        }

        @Override
        final Value apply(List<Value> args) {
            BigDecimal accumulator = null;
            long count = 0;
            for (Value arg : args) {
                BigDecimal number = arg.asNumber();
                accumulator = accumulator == null ? number : combine(accumulator, number);
                count++;
            }
            return finish(accumulator, count);
        }

        BigDecimal combine(BigDecimal accumulator, BigDecimal number) {
            return accumulator.add(number, MC);
        }

        Value finish(BigDecimal accumulator, long count) {
            return Value.number(accumulator);
        }
    }

    private static class Max extends Reducing {
        Max() {
            super("MAX");
        }

        @Override
        BigDecimal combine(BigDecimal accumulator, BigDecimal number) {
            return accumulator.max(number);
        }
    }

    private static class Min extends Reducing {
        Min() {
            super("MIN");
        }

        @Override
        BigDecimal combine(BigDecimal accumulator, BigDecimal number) {
            return accumulator.min(number);
        }
    }

    private static class Sum extends Reducing {
        Sum() {
            super("SUM");
        }
    }

    private static class Product extends Reducing {
        Product() {
            super("PRODUCT");
        }

        @Override
        BigDecimal combine(BigDecimal accumulator, BigDecimal number) {
            return accumulator.multiply(number, MC);
        }
    }

    /** {@code AVERAGE} of at least one argument; the count is never 0 at this arity. */
    private static class Average extends Reducing {
        Average() {
            super("AVERAGE");
        }

        @Override
        Value finish(BigDecimal accumulator, long count) {
            return Value.number(accumulator.divide(BigDecimal.valueOf(count), MC));
        }
    }

    /**
     * {@code CEILING(x, [significance])} — classic Excel: away from zero to a multiple of the
     * significance; a negative number with a positive significance is {@code #NUM!}; a zero
     * significance gives 0. The multiple is computed exactly over scaled {@code BigInteger}s.
     */
    private static class Ceiling extends Fn {
        Ceiling() {
            super("CEILING", 1, 2);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal x = num(args, 0);
            BigDecimal significance = args.size() > 1 ? num(args, 1) : BigDecimal.ONE;
            if (significance.signum() == 0) {
                return Value.number(BigDecimal.ZERO);
            }
            // Classic Excel: a negative number with a positive significance is #NUM!.
            if (x.signum() * significance.signum() < 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(multiple(x, significance, true));
        }
    }

    /**
     * {@code FLOOR(x, [significance])} — classic Excel: toward zero to a multiple of the
     * significance; a zero significance is {@code #DIV/0!}; a number and significance of opposite
     * signs is {@code #NUM!}.
     */
    private static class Floor extends Fn {
        Floor() {
            super("FLOOR", 1, 2);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal x = num(args, 0);
            BigDecimal significance = args.size() > 1 ? num(args, 1) : BigDecimal.ONE;
            if (significance.signum() == 0) {
                return Value.error(FormulaError.DIV_ZERO);
            }
            if (x.signum() * significance.signum() < 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(multiple(x, significance, false));
        }
    }

    /**
     * {@code significance × ceil/floor(x / significance)} exactly: both values are scaled to a
     * common scale so the quotient's rounding direction is decided on integers.
     */
    private static BigDecimal multiple(BigDecimal x, BigDecimal significance, boolean ceiling) {
        int scale = Math.max(x.scale(), significance.scale());
        BigInteger numerator = x.setScale(scale).unscaledValue();
        BigInteger divisor = significance.setScale(scale).unscaledValue();
        BigInteger quotient = ceiling
                ? ceilDiv(numerator, divisor)
                : floorDiv(numerator, divisor);
        return new BigDecimal(quotient.multiply(divisor), scale);
    }

    private static BigInteger floorDiv(BigInteger numerator, BigInteger divisor) {
        BigInteger[] quotientRemainder = numerator.divideAndRemainder(divisor);
        if (quotientRemainder[1].signum() != 0
                && quotientRemainder[1].signum() != divisor.signum()) {
            quotientRemainder[0] = quotientRemainder[0].subtract(BigInteger.ONE);
        }
        return quotientRemainder[0];
    }

    private static BigInteger ceilDiv(BigInteger numerator, BigInteger divisor) {
        return floorDiv(numerator.negate(), divisor).negate();
    }
}
