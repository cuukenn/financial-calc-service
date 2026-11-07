package com.fincalc.rule.expression;

import com.fincalc.common.error.BusinessException;
import com.fincalc.rule.RuleErrorCode;
import com.fincalc.rule.expression.Token.Type;
import com.fincalc.rule.function.FormulaFunction;
import com.fincalc.rule.function.FunctionRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Recursive-descent parser turning a {@link Lexer} token list into an {@link AstNode} tree,
 * validating identifiers and function calls at compile time.
 *
 * <p>Precedence, lowest to highest (§8.4 binding):
 * {@code expr → or → and → comparison → additive → term → power → unary → primary}.
 * {@code ^} is left-associative ({@code 2^3^2 = (2^3)^2}, Excel-style) and unary
 * {@code -}/{@code !} bind tighter than {@code ^} ({@code -2^2 = 4}).
 *
 * <p><b>Compile-time checks.</b> An identifier is either a whitelisted variable or a function
 * call; anything else raises {@link RuleErrorCode#UNKNOWN_VARIABLE} at parse time, so a rule can
 * never read an identifier the context does not define. A function call resolves against the
 * passed {@link FunctionRegistry} — an unknown name, or an argument count outside
 * {@code [minArity, maxArity]}, raises {@link RuleErrorCode#UNKNOWN_FUNCTION}. There is no
 * assignment, member access or {@code new} in the grammar, and the lexer rejects the dot, which
 * structurally rules out reflection-style escapes.
 *
 * <p><b>Depth cap (design §8.6 单表达式深度 ≤ 64).</b> Every nesting construct — a parenthesized
 * group, a unary operator, a binary operator, a function call — is counted; the 65th level raises
 * the {@code FCS-RULE-3004} syntax error naming the nesting overflow, instead of an uncatchable
 * {@code StackOverflowError}. Every AST level is counted, so the cap also bounds the recursive
 * {@link AstNode#eval(EvalContext)} interpreter (eval depth ≤ parse depth); parenthesized groups
 * and unary {@code +} cost parse depth only, which keeps the parser's own recursion bounded too.
 */
final class Parser {

    /** The only variables an expression may reference; identifiers are matched upper-cased. */
    private static final Set<String> VARIABLES = Set.of(
            "PERIOD", "BALANCE", "DAYS", "RATE", "INDEX",
            "IS_LAST", "PRINCIPAL", "TOTAL_PERIODS", "RESIDUAL");

    /** §8.6's 单表达式深度 ≤ 64; the same cap bounds eval recursion. */
    private static final int MAX_DEPTH = 64;

    private final List<Token> tokens;
    private final FunctionRegistry functions;
    /** Extra identifiers accepted beside the whitelist: a script body's formal parameters. */
    private final Set<String> extraVariables;
    private int pos;
    private int depth;

    Parser(List<Token> tokens, FunctionRegistry functions) {
        this(tokens, functions, Set.of());
    }

    /**
     * Parser variant for external function script bodies (Task 27): {@code additionalVariables}
     * are the definition's formal parameters, accepted as local variable references beside the
     * nine whitelisted period variables. Names are matched upper-cased.
     */
    Parser(List<Token> tokens, FunctionRegistry functions, Set<String> additionalVariables) {
        this.tokens = tokens;
        this.functions = functions;
        Set<String> upper = new java.util.HashSet<>();
        for (String name : additionalVariables) {
            upper.add(name.toUpperCase(java.util.Locale.ROOT));
        }
        this.extraVariables = upper;
    }

    /** Parses the whole token list into one tree; rejects trailing tokens. */
    AstNode parse() {
        AstNode root = parseOr();
        if (peek().type() != Type.EOF) {
            throw unexpected(peek());
        }
        return root;
    }

    /** {@code or → and (|| and)*} */
    private AstNode parseOr() {
        AstNode node = parseAnd();
        while (peek().type() == Type.OR) {
            node = fold(AstNode.Op.OR, node, advance(), this::parseAnd);
        }
        return node;
    }

    /** {@code and → comparison (&& comparison)*} */
    private AstNode parseAnd() {
        AstNode node = parseComparison();
        while (peek().type() == Type.AND) {
            node = fold(AstNode.Op.AND, node, advance(), this::parseComparison);
        }
        return node;
    }

    /** {@code comparison → additive ((= <> < <= > >=) additive)*}, left-associative */
    private AstNode parseComparison() {
        AstNode node = parseAdditive();
        while (true) {
            switch (peek().type()) {
                case EQ:
                    node = fold(AstNode.Op.EQ, node, advance(), this::parseAdditive);
                    break;
                case NE:
                    node = fold(AstNode.Op.NE, node, advance(), this::parseAdditive);
                    break;
                case LT:
                    node = fold(AstNode.Op.LT, node, advance(), this::parseAdditive);
                    break;
                case LE:
                    node = fold(AstNode.Op.LE, node, advance(), this::parseAdditive);
                    break;
                case GT:
                    node = fold(AstNode.Op.GT, node, advance(), this::parseAdditive);
                    break;
                case GE:
                    node = fold(AstNode.Op.GE, node, advance(), this::parseAdditive);
                    break;
                default:
                    return node;
            }
        }
    }

    /** {@code additive → term ((+ -) term)*} */
    private AstNode parseAdditive() {
        AstNode node = parseTerm();
        while (peek().type() == Type.PLUS || peek().type() == Type.MINUS) {
            Token operator = advance();
            AstNode.Op op = operator.type() == Type.PLUS ? AstNode.Op.ADD : AstNode.Op.SUB;
            node = fold(op, node, operator, this::parseTerm);
        }
        return node;
    }

    /** {@code term → power ((* /) power)*} */
    private AstNode parseTerm() {
        AstNode node = parsePower();
        while (peek().type() == Type.STAR || peek().type() == Type.SLASH) {
            Token operator = advance();
            AstNode.Op op = operator.type() == Type.STAR ? AstNode.Op.MUL : AstNode.Op.DIV;
            node = fold(op, node, operator, this::parsePower);
        }
        return node;
    }

    /** {@code power → unary (^ unary)*}, left-associative like Excel */
    private AstNode parsePower() {
        AstNode node = parseUnary();
        while (peek().type() == Type.CARET) {
            node = fold(AstNode.Op.POW, node, advance(), this::parseUnary);
        }
        return node;
    }

    /** {@code unary → (! | - | +) unary | primary}; a leading {@code +} is a no-op */
    private AstNode parseUnary() {
        Token token = peek();
        switch (token.type()) {
            case MINUS: {
                advance();
                push(token);
                AstNode node = new AstNode.UnaryNode(AstNode.Op.NEG, parseUnary());
                depth--;
                return node;
            }
            case NOT: {
                advance();
                push(token);
                AstNode node = new AstNode.UnaryNode(AstNode.Op.NOT, parseUnary());
                depth--;
                return node;
            }
            case PLUS: {
                advance();
                push(token);
                AstNode node = parseUnary();
                depth--;
                return node;
            }
            default:
                return parsePrimary();
        }
    }

    /** {@code primary → NUMBER | STRING | TRUE | FALSE | BLANK | IDENT | IDENT(args) | ( or )} */
    private AstNode parsePrimary() {
        Token token = peek();
        switch (token.type()) {
            case NUMBER:
                advance();
                return new AstNode.LiteralNode(Value.number(new BigDecimal(token.text())));
            case STRING:
                advance();
                return new AstNode.LiteralNode(Value.text(token.text()));
            case TRUE: {
                advance();
                // TRUE()/FALSE() with parentheses are calls of the registered logical functions;
                // a bare TRUE/FALSE stays the literal (Excel supports both spellings).
                if (peek().type() == Type.LPAREN) {
                    pos--;
                    return parseIdentifier();
                }
                return new AstNode.LiteralNode(Value.bool(true));
            }
            case FALSE: {
                advance();
                if (peek().type() == Type.LPAREN) {
                    pos--;
                    return parseIdentifier();
                }
                return new AstNode.LiteralNode(Value.bool(false));
            }
            case BLANK:
                advance();
                return new AstNode.LiteralNode(Value.blank());
            case IDENT:
                return parseIdentifier();
            case LPAREN: {
                advance();
                push(token);
                AstNode grouped = parseOr();
                if (peek().type() != Type.RPAREN) {
                    throw unexpected(peek());
                }
                advance();
                depth--;
                return grouped;
            }
            default:
                throw unexpected(token);
        }
    }

    /** A whitelisted variable reference (or a script formal parameter) or a validated call. */
    private AstNode parseIdentifier() {
        Token nameToken = advance();
        if (peek().type() != Type.LPAREN) {
            String name = nameToken.text();
            if (!VARIABLES.contains(name) && !extraVariables.contains(name)) {
                throw new BusinessException(RuleErrorCode.UNKNOWN_VARIABLE, name);
            }
            return new AstNode.VarNode(name);
        }
        return parseCall(nameToken);
    }

    private AstNode parseCall(Token nameToken) {
        String code = nameToken.text();
        advance(); // LPAREN
        push(nameToken);
        List<AstNode> args = new ArrayList<>();
        if (peek().type() != Type.RPAREN) {
            args.add(parseOr());
            while (peek().type() == Type.COMMA) {
                advance();
                args.add(parseOr());
            }
        }
        if (peek().type() != Type.RPAREN) {
            throw unexpected(peek());
        }
        advance(); // RPAREN
        depth--;

        FormulaFunction function = functions.find(code);
        if (function == null) {
            throw new BusinessException(RuleErrorCode.UNKNOWN_FUNCTION, code);
        }
        int count = args.size();
        if (count < function.minArity()
                || (function.maxArity() >= 0 && count > function.maxArity())) {
            throw new BusinessException(RuleErrorCode.UNKNOWN_FUNCTION,
                    code + " " + arityText(function) + ", 实际 " + count);
        }
        return new AstNode.CallNode(code, args);
    }

    /**
     * Folds one binary operator into {@code left}, counting the nesting level (each operator
     * application deepens the AST's left spine).
     */
    private AstNode fold(AstNode.Op op, AstNode left, Token operator, Supplier<AstNode> operand) {
        push(operator);
        AstNode node = new AstNode.BinaryNode(op, left, operand.get());
        depth--;
        return node;
    }

    /**
     * Enters one nesting level, raising the {@code FCS-RULE-3004} syntax error past
     * {@code MAX_DEPTH} levels (§8.6). The parser is discarded on any parse error, so the counter
     * needs no unwinding on the throw path.
     */
    private void push(Token token) {
        depth++;
        if (depth > MAX_DEPTH) {
            throw new BusinessException(RuleErrorCode.EXPRESSION_SYNTAX_ERROR,
                    "表达式嵌套过深: 深度 " + depth + " > " + MAX_DEPTH
                            + " (第 " + token.line() + " 行第 " + token.col() + " 列)");
        }
    }

    private static String arityText(FormulaFunction function) {
        int min = function.minArity();
        int max = function.maxArity();
        if (max < 0) {
            return "至少需要 " + min + " 个参数";
        }
        if (min == max) {
            return "恰好需要 " + min + " 个参数";
        }
        return "需要 " + min + " 到 " + max + " 个参数";
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token advance() {
        Token token = tokens.get(pos);
        if (token.type() != Type.EOF) {
            pos++;
        }
        return token;
    }

    private static BusinessException unexpected(Token token) {
        String detail = token.type() == Type.EOF
                ? "第 " + token.line() + " 行第 " + token.col() + " 列 表达式意外结束"
                : "第 " + token.line() + " 行第 " + token.col() + " 列 意外的 token '"
                        + token.text() + "'";
        return new BusinessException(RuleErrorCode.EXPRESSION_SYNTAX_ERROR, detail);
    }
}
