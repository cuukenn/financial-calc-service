package com.fincalc.rule.expression;

/**
 * Kind carried by an immutable {@link Value} in the expression engine.
 *
 * <p>The set mirrors the spreadsheet model the rules speak: a period variable is a
 * {@code NUMBER}, an unset cell is {@code BLANK}, a failed computation degrades to an
 * {@code ERROR} value (design §8.5) instead of an exception, so {@code IFERROR} can catch it
 * later.
 */
public enum ValueType {

    /** A decimal number ({@code BigDecimal}); the only kind money or rates ever use. */
    NUMBER,

    /** A string literal, for example a sequence name compared with {@code =}. */
    TEXT,

    /** A boolean produced by {@code TRUE} / {@code FALSE} literals, comparisons or logic. */
    BOOLEAN,

    /** An empty cell: unset variables and the {@code BLANK} literal. */
    BLANK,

    /** A failed computation carrying a {@link FormulaError}, propagating like Excel errors. */
    ERROR
}
