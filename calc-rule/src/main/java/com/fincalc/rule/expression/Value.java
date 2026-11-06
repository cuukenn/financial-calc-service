package com.fincalc.rule.expression;

import com.fincalc.common.error.BusinessException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable result of one expression evaluation: a {@link ValueType} plus the raw value behind it.
 *
 * <p>Every factory snapshots a non-null raw value ({@link #blank()} carries {@code null}); the
 * coercion accessors convert on demand and document their behaviour per type:
 *
 * <ul>
 *   <li>{@link #asNumber()} — {@code BLANK} counts as 0, {@code BOOLEAN} as 1/0, numeric
 *       {@code TEXT} parses, anything else raises {@code #VALUE!}; an {@code ERROR} raises with
 *       the error's own code;</li>
 *   <li>{@link #asBoolean()} — a non-zero {@code NUMBER} is true, {@code BLANK} is false, the
 *       texts {@code TRUE}/{@code FALSE} parse, anything else raises {@code #VALUE!};</li>
 *   <li>{@link #asText()} — numbers render via {@code toPlainString()}, booleans as
 *       {@code TRUE}/{@code FALSE}, {@code BLANK} as the empty string.</li>
 * </ul>
 *
 * <p>The raises are {@link BusinessException}s carrying a {@link FormulaError}; the evaluation
 * nodes convert them back into {@code ERROR} values, so inside an expression they behave as
 * values, while direct callers (rule orchestration) see a coded exception.
 */
public final class Value {

    private static final Value BLANK_VALUE = new Value(ValueType.BLANK, null);

    private final ValueType type;
    private final Object raw;

    private Value(ValueType type, Object raw) {
        this.type = Objects.requireNonNull(type, "type");
        this.raw = raw;
    }

    /** A number value; never {@code null}. */
    public static Value number(BigDecimal number) {
        return new Value(ValueType.NUMBER, Objects.requireNonNull(number, "number"));
    }

    /** A text value; never {@code null}. */
    public static Value text(String text) {
        return new Value(ValueType.TEXT, Objects.requireNonNull(text, "text"));
    }

    /** A boolean value. */
    public static Value bool(boolean value) {
        return new Value(ValueType.BOOLEAN, value);
    }

    /** The empty-cell value. */
    public static Value blank() {
        return BLANK_VALUE;
    }

    /** A failed computation carrying its Excel error mapping. */
    public static Value error(FormulaError error) {
        return new Value(ValueType.ERROR, Objects.requireNonNull(error, "error"));
    }

    /** The kind of this value. */
    public ValueType type() {
        return type;
    }

    /** The raw carrier: {@code BigDecimal}, {@code String}, {@code Boolean}, {@code null} for {@code BLANK}, or {@link FormulaError}. */
    public Object raw() {
        return raw;
    }

    /**
     * Coerces this value to a number.
     *
     * @return the number; {@code BLANK} counts as 0 and {@code BOOLEAN} as 1/0
     * @throws BusinessException with {@link FormulaError#VALUE_ERROR} when a non-numeric text
     *                           cannot parse, or with the value's own {@link FormulaError} when
     *                           this is an {@code ERROR} value
     */
    public BigDecimal asNumber() {
        switch (type) {
            case NUMBER:
                return (BigDecimal) raw;
            case BLANK:
                return BigDecimal.ZERO;
            case BOOLEAN:
                return (Boolean) raw ? BigDecimal.ONE : BigDecimal.ZERO;
            case TEXT:
                return parseNumber((String) raw);
            case ERROR:
            default:
                throw new BusinessException((FormulaError) raw);
        }
    }

    /**
     * Coerces this value to a boolean.
     *
     * @return {@code true} for a non-zero number and for the text {@code TRUE}; {@code false} for
     *         zero, {@code BLANK} and the text {@code FALSE}
     * @throws BusinessException with {@link FormulaError#VALUE_ERROR} for any other text, or with
     *                           the value's own {@link FormulaError} when this is an {@code ERROR}
     *                           value
     */
    public boolean asBoolean() {
        switch (type) {
            case BOOLEAN:
                return (Boolean) raw;
            case NUMBER:
                return ((BigDecimal) raw).compareTo(BigDecimal.ZERO) != 0;
            case BLANK:
                return false;
            case TEXT:
                String normalized = ((String) raw).trim().toUpperCase(Locale.ROOT);
                if ("TRUE".equals(normalized)) {
                    return true;
                }
                if ("FALSE".equals(normalized)) {
                    return false;
                }
                throw new BusinessException(FormulaError.VALUE_ERROR);
            case ERROR:
            default:
                throw new BusinessException((FormulaError) raw);
        }
    }

    /**
     * Coerces this value to text.
     *
     * @return the text; numbers render via {@code toPlainString()}, booleans as
     *         {@code TRUE}/{@code FALSE}, {@code BLANK} as the empty string
     * @throws BusinessException with the value's own {@link FormulaError} when this is an
     *                           {@code ERROR} value
     */
    public String asText() {
        switch (type) {
            case TEXT:
                return (String) raw;
            case NUMBER:
                return ((BigDecimal) raw).toPlainString();
            case BOOLEAN:
                return (Boolean) raw ? "TRUE" : "FALSE";
            case BLANK:
                return "";
            case ERROR:
            default:
                throw new BusinessException((FormulaError) raw);
        }
    }

    private static BigDecimal parseNumber(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(FormulaError.VALUE_ERROR);
        }
    }

    /** Shared numeric context of the engine: 34 significant digits, half-even. */
    static MathContext mathContext() {
        return MathContext.DECIMAL128;
    }

    @Override
    public String toString() {
        return type == ValueType.BLANK ? "BLANK" : String.valueOf(raw);
    }
}
