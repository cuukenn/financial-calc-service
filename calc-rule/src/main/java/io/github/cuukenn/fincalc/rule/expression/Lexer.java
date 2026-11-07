package io.github.cuukenn.fincalc.rule.expression;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.rule.RuleErrorCode;
import io.github.cuukenn.fincalc.rule.expression.Token.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Turns an expression source string into a {@link Token} list with 1-based line/column positions,
 * so syntax errors can point at the offending character.
 *
 * <p>Recognizes the §8.4 operator set ({@code = <> < > <= >=}, the aliases {@code ==}/{@code !=},
 * {@code + - * / ^}, {@code && || !}, parentheses, the argument comma), decimal numbers,
 * double-quoted string literals (no escape sequences, matching Excel), and identifiers —
 * normalized to upper case, with {@code TRUE}/{@code FALSE}/{@code BLANK} recognized as literals
 * in any casing.
 *
 * <p>The dot is deliberately not part of the language: any {@code .} fails here with a syntax
 * error, which is what keeps property access and method calls out of rules at the lexical level.
 */
final class Lexer {

    private final String source;
    private int pos;
    private int line = 1;
    private int col = 1;

    Lexer(String source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Tokenizes the whole source, ending with a {@link Type#EOF} token.
     *
     * <p>Identifiers may additionally carry non-ASCII letters (Task 27): external function script
     * names such as {@code 含税金额} must lex as one identifier both inside {@code .fcs} bodies
     * and inside rule expressions that call script functions. ASCII keywords and the
     * {@code TRUE}/{@code FALSE}/{@code BLANK} literals are unaffected.
     */
    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (pos >= source.length()) {
                tokens.add(Token.of(Type.EOF, "", line, col));
                return tokens;
            }
            tokens.add(nextToken());
        }
    }

    private Token nextToken() {
        int startLine = line;
        int startCol = col;
        char c = source.charAt(pos);
        switch (c) {
            case '0': case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                return number(startLine, startCol);
            case '"':
                return string(startLine, startCol);
            case 'A': case 'B': case 'C': case 'D': case 'E': case 'F': case 'G': case 'H':
            case 'I': case 'J': case 'K': case 'L': case 'M': case 'N': case 'O': case 'P':
            case 'Q': case 'R': case 'S': case 'T': case 'U': case 'V': case 'W': case 'X':
            case 'Y': case 'Z':
            case 'a': case 'b': case 'c': case 'd': case 'e': case 'f': case 'g': case 'h':
            case 'i': case 'j': case 'k': case 'l': case 'm': case 'n': case 'o': case 'p':
            case 'q': case 'r': case 's': case 't': case 'u': case 'v': case 'w': case 'x':
            case 'y': case 'z':
            case '_':
                return word(startLine, startCol);
            case '+':
                return single(Type.PLUS, startLine, startCol);
            case '-':
                return single(Type.MINUS, startLine, startCol);
            case '*':
                return single(Type.STAR, startLine, startCol);
            case '/':
                return single(Type.SLASH, startLine, startCol);
            case '^':
                return single(Type.CARET, startLine, startCol);
            case '(':
                return single(Type.LPAREN, startLine, startCol);
            case ')':
                return single(Type.RPAREN, startLine, startCol);
            case ',':
                return single(Type.COMMA, startLine, startCol);
            case '=':
            case '!':
            case '<':
            case '>':
                return comparison(startLine, startCol);
            case '&':
                return doubled(Type.AND, startLine, startCol);
            case '|':
                return doubled(Type.OR, startLine, startCol);
            default:
                // Non-ASCII letters (e.g. CJK script function names) start identifiers too.
                if (isIdentStart(c)) {
                    return word(startLine, startCol);
                }
                throw syntax(startLine, startCol, "不允许的字符 '" + c + "'");
        }
    }

    private Token number(int startLine, int startCol) {
        int start = pos;
        while (isDigit(current())) {
            advance();
        }
        if (current() == '.') {
            if (!isDigit(next())) {
                throw syntax(startLine, startCol, "小数点后缺少数字");
            }
            advance();
            while (isDigit(current())) {
                advance();
            }
        }
        return Token.of(Type.NUMBER, source.substring(start, pos), startLine, startCol);
    }

    private Token string(int startLine, int startCol) {
        advance(); // opening quote
        int start = pos;
        while (pos < source.length() && current() != '"') {
            if (current() == '\n') {
                throw syntax(startLine, startCol, "字符串缺少结束引号");
            }
            advance();
        }
        if (pos >= source.length()) {
            throw syntax(startLine, startCol, "字符串缺少结束引号");
        }
        String value = source.substring(start, pos);
        advance(); // closing quote
        return Token.of(Type.STRING, value, startLine, startCol);
    }

    private Token word(int startLine, int startCol) {
        int start = pos;
        while (isIdentPart(current())) {
            advance();
        }
        String word = source.substring(start, pos).toUpperCase(Locale.ROOT);
        switch (word) {
            case "TRUE":
                return Token.of(Type.TRUE, word, startLine, startCol);
            case "FALSE":
                return Token.of(Type.FALSE, word, startLine, startCol);
            case "BLANK":
                return Token.of(Type.BLANK, word, startLine, startCol);
            default:
                return Token.of(Type.IDENT, word, startLine, startCol);
        }
    }

    private Token comparison(int startLine, int startCol) {
        char c = advance();
        char next = current();
        switch (c) {
            case '=':
                if (next == '=') {
                    advance();
                }
                return Token.of(Type.EQ, "=", startLine, startCol);
            case '!':
                if (next == '=') {
                    advance();
                    return Token.of(Type.NE, "!=", startLine, startCol);
                }
                return Token.of(Type.NOT, "!", startLine, startCol);
            case '<':
                if (next == '=') {
                    advance();
                    return Token.of(Type.LE, "<=", startLine, startCol);
                }
                if (next == '>') {
                    advance();
                    return Token.of(Type.NE, "<>", startLine, startCol);
                }
                return Token.of(Type.LT, "<", startLine, startCol);
            case '>':
            default:
                if (next == '=') {
                    advance();
                    return Token.of(Type.GE, ">=", startLine, startCol);
                }
                return Token.of(Type.GT, ">", startLine, startCol);
        }
    }

    /** Reads a two-character operator ({@code &&} or {@code ||}); a lone character is an error. */
    private Token doubled(Type type, int startLine, int startCol) {
        char c = advance();
        if (current() != c) {
            throw syntax(startLine, startCol, "单个 '" + c + "' 无效, 需要 '" + c + c + "'");
        }
        advance();
        return Token.of(type, String.valueOf(c) + c, startLine, startCol);
    }

    private Token single(Type type, int startLine, int startCol) {
        String text = String.valueOf(advance());
        return Token.of(type, text, startLine, startCol);
    }

    private void skipWhitespace() {
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\f') {
                advance();
            } else if (c == '\n') {
                advance();
                line++;
                col = 1;
            } else if (c == '\r') {
                advance();
                if (current() == '\n') {
                    advance();
                }
                line++;
                col = 1;
            } else {
                return;
            }
        }
    }

    private char current() {
        return pos < source.length() ? source.charAt(pos) : '\0';
    }

    private char next() {
        return pos + 1 < source.length() ? source.charAt(pos + 1) : '\0';
    }

    private char advance() {
        char c = source.charAt(pos++);
        col++;
        return c;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** ASCII letters, the underscore, or any non-ASCII letter (CJK function names, Task 27). */
    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_'
                || (c > 127 && Character.isLetter(c));
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    /** Syntax error pointing at the 1-based position where the problem was found. */
    private static BusinessException syntax(int line, int col, String detail) {
        return new BusinessException(RuleErrorCode.EXPRESSION_SYNTAX_ERROR,
                "第 " + line + " 行第 " + col + " 列 " + detail);
    }
}
