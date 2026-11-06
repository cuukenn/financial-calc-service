package com.fincalc.rule.expression;

import java.util.Objects;

/**
 * One lexical unit of an expression, carrying the 1-based line and column where it starts so that
 * lexer and parser errors can point at the offending position.
 *
 * <p>Identifier and keyword text is normalized to upper case (the language is case-insensitive);
 * string literals keep their exact content.
 */
public final class Token {

    /** Lexical categories the lexer produces. */
    public enum Type {

        /** A decimal number such as {@code 12} or {@code 0.05}. */
        NUMBER,

        /** A double-quoted string literal. */
        STRING,

        /** An identifier, normalized to upper case. */
        IDENT,

        /** The {@code TRUE} literal, any casing. */
        TRUE,

        /** The {@code FALSE} literal, any casing. */
        FALSE,

        /** The {@code BLANK} literal, any casing. */
        BLANK,

        /** {@code +} */ PLUS,
        /** {@code -} */ MINUS,
        /** {@code *} */ STAR,
        /** {@code /} */ SLASH,
        /** {@code ^} */ CARET,
        /** {@code =} or {@code ==} */ EQ,
        /** {@code <>} or {@code !=} */ NE,
        /** {@code <} */ LT,
        /** {@code <=} */ LE,
        /** {@code >} */ GT,
        /** {@code >=} */ GE,
        /** {@code &&} */ AND,
        /** {@code ||} */ OR,
        /** {@code !} */ NOT,
        /** {@code (} */ LPAREN,
        /** {@code )} */ RPAREN,
        /** {@code ,} separating function arguments */ COMMA,
        /** End of input; always the last token. */
        EOF
    }

    private final Type type;
    private final String text;
    private final int line;
    private final int col;

    private Token(Type type, String text, int line, int col) {
        this.type = Objects.requireNonNull(type, "type");
        this.text = Objects.requireNonNull(text, "text");
        this.line = line;
        this.col = col;
    }

    /** Builds a token at the given 1-based position. */
    public static Token of(Type type, String text, int line, int col) {
        return new Token(type, text, line, col);
    }

    /** The lexical category. */
    public Type type() {
        return type;
    }

    /** The normalized text: upper-cased identifiers and keywords, raw lexeme otherwise. */
    public String text() {
        return text;
    }

    /** 1-based line of the token's first character. */
    public int line() {
        return line;
    }

    /** 1-based column of the token's first character. */
    public int col() {
        return col;
    }

    @Override
    public String toString() {
        return type + "('" + text + "'@" + line + ":" + col + ")";
    }
}
