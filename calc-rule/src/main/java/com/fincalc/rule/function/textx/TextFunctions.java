package com.fincalc.rule.function.textx;

import com.fincalc.common.error.BusinessException;
import com.fincalc.rule.expression.AstNode;
import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.FormulaError;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.function.FunctionRegistry;
import com.fincalc.rule.function.datex.DateSerialize;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * The v1 text family (design §8.5): 11 Excel-compatible functions. Non-text arguments coerce
 * through {@link Value#asText()} (numbers render as plain strings, booleans as TRUE/FALSE,
 * blanks as empty), matching Excel's direct-argument behaviour. {@code TRIM} is Excel's: it
 * strips leading/trailing spaces <em>and</em> collapses internal runs of spaces to single spaces.
 *
 * <p><b>{@code TEXT} is a documented pragmatic subset.</b> Excel's full number-format grammar
 * (colour sections, {@code E+} scientific, {@code @} sections, conditional sections) is out of v1
 * scope; an unsupported pattern is {@code #VALUE!}, never a silent mis-render. Supported:
 * numeric patterns of {@code 0}/{@code #} with optional thousands separator {@code ,} and
 * decimal point, plus a trailing percent {@code %} (the value is scaled by 100); and date
 * patterns of {@code y}/{@code m}/{@code d} runs ({@code yyyy}, {@code yy}, {@code mm},
 * {@code m}, {@code dd}, {@code d}) with literal separators, applied to a date serial.
 *
 * <p>Every function is stateless and pure; failures surface as {@code ERROR} values.
 */
public final class TextFunctions {

    private static final MathContext MC = MathContext.DECIMAL128;

    private TextFunctions() {
    }

    /** Registers the 11 text functions. */
    public static FunctionRegistry register(FunctionRegistry registry) {
        return registry
                .register(new Concat())
                .register(new Concatenate())
                .register(new Left())
                .register(new Right())
                .register(new Mid())
                .register(new Len())
                .register(new Trim())
                .register(new Upper())
                .register(new Lower())
                .register(new Text())
                .register(new ValueFn());
    }

    /** Template shared by every text function: coerce failures into {@code ERROR} values. */
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

    private static String text(List<Value> args, int index) {
        return args.get(index).asText();
    }

    private static int toInt(BigDecimal value) {
        return value.setScale(0, RoundingMode.DOWN).intValueExact();
    }

    private static class Concat extends Fn {
        Concat() {
            super("CONCAT", 1, -1);
        }

        @Override
        Value apply(List<Value> args) {
            StringBuilder joined = new StringBuilder();
            for (Value arg : args) {
                joined.append(arg.asText());
            }
            return Value.text(joined.toString());
        }
    }

    private static class Concatenate extends Fn {
        Concatenate() {
            super("CONCATENATE", 1, -1);
        }

        @Override
        Value apply(List<Value> args) {
            StringBuilder joined = new StringBuilder();
            for (Value arg : args) {
                joined.append(arg.asText());
            }
            return Value.text(joined.toString());
        }
    }

    /** {@code LEFT(text, [count])} — count defaults to 1; negative is {@code #VALUE!}. */
    private static class Left extends Fn {
        Left() {
            super("LEFT", 1, 2);
        }

        @Override
        Value apply(List<Value> args) {
            int count = args.size() > 1 ? toInt(args.get(1).asNumber()) : 1;
            if (count < 0) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            String value = text(args, 0);
            return Value.text(value.substring(0, Math.min(count, value.length())));
        }
    }

    /** {@code RIGHT(text, [count])} — count defaults to 1; negative is {@code #VALUE!}. */
    private static class Right extends Fn {
        Right() {
            super("RIGHT", 1, 2);
        }

        @Override
        Value apply(List<Value> args) {
            int count = args.size() > 1 ? toInt(args.get(1).asNumber()) : 1;
            if (count < 0) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            String value = text(args, 0);
            return Value.text(value.substring(Math.max(0, value.length() - count)));
        }
    }

    /** {@code MID(text, start, length)} — 1-based start; a bad range is {@code #VALUE!}. */
    private static class Mid extends Fn {
        Mid() {
            super("MID", 3, 3);
        }

        @Override
        Value apply(List<Value> args) {
            int start = toInt(args.get(1).asNumber());
            int length = toInt(args.get(2).asNumber());
            if (start < 1 || length < 0) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            String value = text(args, 0);
            int from = Math.min(start - 1, value.length());
            return Value.text(value.substring(from, Math.min(from + length, value.length())));
        }
    }

    private static class Len extends Fn {
        Len() {
            super("LEN", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.number(BigDecimal.valueOf(text(args, 0).length()));
        }
    }

    /** Excel's TRIM: strip the ends and collapse internal space runs to one space. */
    private static class Trim extends Fn {
        Trim() {
            super("TRIM", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.text(text(args, 0).trim().replaceAll(" +", " "));
        }
    }

    private static class Upper extends Fn {
        Upper() {
            super("UPPER", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.text(text(args, 0).toUpperCase(Locale.ROOT));
        }
    }

    private static class Lower extends Fn {
        Lower() {
            super("LOWER", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            return Value.text(text(args, 0).toLowerCase(Locale.ROOT));
        }
    }

    /** {@code TEXT(value, format)} — the documented subset; see the class javadoc. */
    private static class Text extends Fn {
        Text() {
            super("TEXT", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            String format = text(args, 1);
            for (int i = 0; i < format.length(); i++) {
                char c = format.charAt(i);
                if (c == 'y' || c == 'Y' || c == 'm' || c == 'M' || c == 'd' || c == 'D') {
                    return renderDate(args.get(0).asNumber(), format);
                }
            }
            return renderNumber(args.get(0).asNumber(), format);
        }

        private Value renderDate(BigDecimal serial, String format) {
            LocalDate date;
            try {
                date = DateSerialize.toLocalDate(serial);
            } catch (BusinessException ex) {
                return Value.error((FormulaError) ex.errorCode());
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < format.length(); ) {
                char c = format.charAt(i);
                if (c == 'y' || c == 'Y') {
                    int run = runLength(format, i);
                    int year = date.getYear();
                    out.append(run >= 3 ? String.format("%04d", year)
                            : String.format("%02d", year % 100));
                    i += run;
                } else if (c == 'm' || c == 'M') {
                    int run = runLength(format, i);
                    out.append(run >= 2 ? String.format("%02d", date.getMonthValue())
                            : String.valueOf(date.getMonthValue()));
                    i += run;
                } else if (c == 'd' || c == 'D') {
                    int run = runLength(format, i);
                    out.append(run >= 2 ? String.format("%02d", date.getDayOfMonth())
                            : String.valueOf(date.getDayOfMonth()));
                    i += run;
                } else {
                    out.append(c);
                    i++;
                }
            }
            return Value.text(out.toString());
        }

        private int runLength(String format, int start) {
            char c = Character.toLowerCase(format.charAt(start));
            int end = start;
            while (end < format.length()
                    && Character.toLowerCase(format.charAt(end)) == c) {
                end++;
            }
            return end - start;
        }

        private Value renderNumber(BigDecimal number, String format) {
            boolean percent = format.indexOf('%') >= 0;
            boolean group = format.indexOf(',') >= 0;
            // The percent sign is a suffix flag, not a digit placeholder.
            String digits = format.replace("%", "");
            int dot = digits.indexOf('.');
            String intPattern = dot < 0 ? digits : digits.substring(0, dot);
            String decPattern = dot < 0 ? "" : digits.substring(dot + 1);
            if (!isDigitPattern(intPattern) || !isDigitPattern(decPattern)) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            int decimals = digitCount(decPattern);
            int minDecimals = zeroCount(decPattern);
            int minIntDigits = zeroCount(intPattern);
            BigDecimal value = number;
            if (percent) {
                value = value.multiply(BigDecimal.valueOf(100), MC);
            }
            value = value.setScale(decimals, RoundingMode.HALF_UP);
            boolean negative = value.signum() < 0;
            BigDecimal absolute = value.abs();
            String intPart = absolute.setScale(0, RoundingMode.DOWN).toPlainString();
            while (intPart.length() < minIntDigits) {
                intPart = "0" + intPart;
            }
            if (group) {
                intPart = groupThousands(intPart);
            }
            String fraction = "";
            if (decimals > 0) {
                fraction = absolute.setScale(decimals).toPlainString();
                fraction = fraction.substring(fraction.indexOf('.') + 1);
                // Trailing '#' positions may drop trailing zeros; '0' positions hold digits.
                while (fraction.length() > minDecimals && fraction.endsWith("0")) {
                    fraction = fraction.substring(0, fraction.length() - 1);
                }
            }
            return Value.text((negative ? "-" : "") + intPart
                    + (decimals > 0 ? "." + fraction : "")
                    + (percent ? "%" : ""));
        }

        private boolean isDigitPattern(String pattern) {
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c != '0' && c != '#' && c != ',') {
                    return false;
                }
            }
            return true;
        }

        private int digitCount(String pattern) {
            int count = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '0' || c == '#') {
                    count++;
                }
            }
            return count;
        }

        private int zeroCount(String pattern) {
            int count = 0;
            for (int i = 0; i < pattern.length(); i++) {
                if (pattern.charAt(i) == '0') {
                    count++;
                }
            }
            return count;
        }

        private String groupThousands(String digits) {
            StringBuilder grouped = new StringBuilder();
            for (int i = 0; i < digits.length(); i++) {
                if (i > 0 && (digits.length() - i) % 3 == 0) {
                    grouped.append(',');
                }
                grouped.append(digits.charAt(i));
            }
            return grouped.toString();
        }
    }

    /** {@code VALUE(text)} — a trimmed numeric literal; anything else is {@code #VALUE!}. */
    private static class ValueFn extends Fn {
        ValueFn() {
            super("VALUE", 1, 1);
        }

        @Override
        Value apply(List<Value> args) {
            String raw = text(args, 0).trim();
            try {
                return Value.number(new BigDecimal(raw));
            } catch (NumberFormatException ex) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
        }
    }
}
