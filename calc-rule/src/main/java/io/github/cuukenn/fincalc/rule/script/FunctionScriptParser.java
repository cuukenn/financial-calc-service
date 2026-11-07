package io.github.cuukenn.fincalc.rule.script;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.rule.RuleErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Line-oriented parser for {@code .fcs} external function scripts (design §8.6, Task 27).
 *
 * <p>Grammar — one construct per line; blank lines and {@code #} comments are skipped; keywords
 * are matched case-insensitively; names and identifiers are normalized to upper case and may
 * carry non-ASCII letters (CJK function names):
 *
 * <pre>
 *   script   := line*
 *   line     := blank | '#' comment | 'VERSION 1'
 *             | ['OVERRIDE'] 'DEFINE' name '(' [param (',' param)*] ')' '=' expression
 * </pre>
 *
 * <p>The first content line must be {@code VERSION 1}. The optional {@code OVERRIDE} keyword
 * sits <em>before</em> {@code DEFINE} on the definition line ({@code OVERRIDE DEFINE ROUND(X, N)
 * = ...}) and licenses replacing a built-in function — without it such a line is rejected with
 * {@code FCS-RULE-3010} at registry build time. The body is everything after the first {@code =}
 * that follows the closing parenthesis, so comparison operators inside the body are untouched.
 *
 * <p>This pass is purely structural: it performs no expression compilation, because definitions
 * may forward-reference each other (and, across files, definitions from later loads), so bodies
 * compile only once the full definition set is known — see
 * {@link io.github.cuukenn.fincalc.rule.function.FunctionRegistry#withDefinitions}. Syntax errors carry
 * {@code 文件:行号} (the source name plus the 1-based line), per the brief.
 */
public final class FunctionScriptParser {

    /** §8.6: 单文件定义数量上限. */
    static final int MAX_DEFINITIONS_PER_FILE = 500;

    private static final String VERSION_LINE = "VERSION 1";
    private static final String OVERRIDE_KEYWORD = "OVERRIDE";
    private static final String DEFINE_KEYWORD = "DEFINE";

    private FunctionScriptParser() {
    }

    /**
     * Parses a script's lines into definitions, in file order.
     *
     * @param text       the whole script text, UTF-8 decoded by the caller; never {@code null}
     * @param sourceName the source the text came from (file name or classpath resource name),
     *                   used in {@code 文件:行号} error messages; never {@code null}
     * @return the definitions in file order; empty when the script defines nothing
     * @throws BusinessException with {@code FCS-RULE-3004} for a missing/duplicate
     *                           {@code VERSION 1} header, an unrecognized line, a malformed
     *                           {@code DEFINE} line, a duplicate parameter name, or more than
     *                           {@value #MAX_DEFINITIONS_PER_FILE} definitions in one file —
     *                           always carrying {@code sourceName} and the 1-based line number
     */
    public static List<FunctionDefinition> parse(String text, String sourceName) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sourceName, "sourceName");
        List<FunctionDefinition> definitions = new ArrayList<>();
        boolean versionSeen = false;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (!versionSeen) {
                if (!upper.equals(VERSION_LINE)) {
                    throw syntax(sourceName, lineNo, "脚本必须以 VERSION 1 开头");
                }
                versionSeen = true;
                continue;
            }
            if (upper.startsWith("VERSION")) {
                throw syntax(sourceName, lineNo, "VERSION 1 只能出现一次");
            }
            definitions.add(parseDefine(trimmed, upper, sourceName, lineNo));
            if (definitions.size() > MAX_DEFINITIONS_PER_FILE) {
                throw syntax(sourceName, lineNo, "定义数量 " + definitions.size()
                        + " 超过单文件上限 " + MAX_DEFINITIONS_PER_FILE);
            }
        }
        return definitions;
    }

    /**
     * Parses one {@code DEFINE} line (already known not to be blank/comment/VERSION). Keywords
     * are matched against the upper-cased copy while the original line supplies names and body.
     */
    private static FunctionDefinition parseDefine(String trimmed, String upper,
            String sourceName, int lineNo) {
        boolean override = false;
        String rest = trimmed;
        if (upper.startsWith(OVERRIDE_KEYWORD + " ") || upper.startsWith(OVERRIDE_KEYWORD + "\t")) {
            override = true;
            rest = trimmed.substring(OVERRIDE_KEYWORD.length()).trim();
            upper = upper.substring(OVERRIDE_KEYWORD.length()).trim();
        }
        if (!upper.startsWith(DEFINE_KEYWORD + " ") && !upper.startsWith(DEFINE_KEYWORD + "\t")) {
            throw syntax(sourceName, lineNo, "无法识别的行, 期望 DEFINE 定义: " + trimmed);
        }
        rest = rest.substring(DEFINE_KEYWORD.length()).trim();

        int open = rest.indexOf('(');
        if (open <= 0) {
            throw syntax(sourceName, lineNo, "定义缺少参数列表: " + trimmed);
        }
        String name = rest.substring(0, open).trim();
        if (!isIdentifier(name)) {
            throw syntax(sourceName, lineNo, "非法函数名 '" + name + "'");
        }
        int close = rest.indexOf(')', open);
        if (close < 0) {
            throw syntax(sourceName, lineNo, "参数列表缺少右括号");
        }
        List<String> parameters = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String parameterPart = rest.substring(open + 1, close).trim();
        if (!parameterPart.isEmpty()) {
            for (String piece : parameterPart.split(",")) {
                String parameter = piece.trim();
                if (!isIdentifier(parameter)) {
                    throw syntax(sourceName, lineNo, "非法参数名 '" + parameter + "'");
                }
                String upperParameter = parameter.toUpperCase(Locale.ROOT);
                if (!seen.add(upperParameter)) {
                    throw syntax(sourceName, lineNo, "重复参数 '" + parameter + "'");
                }
                parameters.add(upperParameter);
            }
        }
        String afterParenthesis = rest.substring(close + 1).trim();
        if (!afterParenthesis.startsWith("=")) {
            throw syntax(sourceName, lineNo, "定义缺少 '='");
        }
        String body = afterParenthesis.substring(1).trim();
        if (body.isEmpty()) {
            throw syntax(sourceName, lineNo, "定义缺少表达式主体");
        }
        return new FunctionDefinition(name, parameters, body, sourceName, lineNo, override);
    }

    /** Letters (ASCII or non-ASCII), underscore and digits; an identifier may not start with a digit. */
    private static boolean isIdentifier(String text) {
        if (text.isEmpty()) {
            return false;
        }
        if (!isIdentStart(text.charAt(0))) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            if (!isIdentPart(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c > 127 && Character.isLetter(c));
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    /** Script-line syntax error carrying the {@code 文件:行号} the brief requires. */
    private static BusinessException syntax(String sourceName, int line, String detail) {
        return new BusinessException(RuleErrorCode.EXPRESSION_SYNTAX_ERROR,
                sourceName + " 第 " + line + " 行: " + detail);
    }
}
