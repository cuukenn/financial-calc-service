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
 */
final class Parser {

    /** The only variables an expression may reference; identifiers are matched upper-cased. */
    private static final Set<String> VARIABLES = Set.of(
            "PERIOD", "BALANCE", "DAYS", "RATE", "INDEX",
            "IS_LAST", "PRINCIPAL", "TOTAL_PERIODS", "RESIDUAL");

    private final List<Token> tokens;
    private final FunctionRegistry functions;
    private int pos;

    Parser(List<Token> tokens, FunctionRegistry functions) {
        this.tokens = tokens;
        this.functions = functions;
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
            advance();
            node = new AstNode.BinaryNode(AstNode.Op.OR, node, parseAnd());
        }
        return node;
    }

    /** {@code and → comparison (&& comparison)*} */
    private AstNode parseAnd() {
        AstNode node = parseComparison();
        while (peek().type() == Type.AND) {
            advance();
            node = new AstNode.BinaryNode(AstNode.Op.AND, node, parseComparison());
        }
        return node;
    }

    /** {@code comparison → additive ((= <> < <= > >=) additive)*}, left-associative */
    private AstNode parseComparison() {
        AstNode node = parseAdditive();
        while (true) {
            switch (peek().type()) {
                case EQ:
                    node = binary(AstNode.Op.EQ, node);
                    break;
                case NE:
                    node = binary(AstNode.Op.NE, node);
                    break;
                case LT:
                    node = binary(AstNode.Op.LT, node);
                    break;
                case LE:
                    node = binary(AstNode.Op.LE, node);
                    break;
                case GT:
                    node = binary(AstNode.Op.GT, node);
                    break;
                case GE:
                    node = binary(AstNode.Op.GE, node);
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
            AstNode.Op op = peek().type() == Type.PLUS ? AstNode.Op.ADD : AstNode.Op.SUB;
            advance();
            node = new AstNode.BinaryNode(op, node, parseTerm());
        }
        return node;
    }

    /** {@code term → power ((* /) power)*} */
    private AstNode parseTerm() {
        AstNode node = parsePower();
        while (peek().type() == Type.STAR || peek().type() == Type.SLASH) {
            AstNode.Op op = peek().type() == Type.STAR ? AstNode.Op.MUL : AstNode.Op.DIV;
            advance();
            node = new AstNode.BinaryNode(op, node, parsePower());
        }
        return node;
    }

    /** {@code power → unary (^ unary)*}, left-associative like Excel */
    private AstNode parsePower() {
        AstNode node = parseUnary();
        while (peek().type() == Type.CARET) {
            advance();
            node = new AstNode.BinaryNode(AstNode.Op.POW, node, parseUnary());
        }
        return node;
    }

    /** {@code unary → (! | - | +) unary | primary}; a leading {@code +} is a no-op */
    private AstNode parseUnary() {
        switch (peek().type()) {
            case MINUS:
                advance();
                return new AstNode.UnaryNode(AstNode.Op.NEG, parseUnary());
            case NOT:
                advance();
                return new AstNode.UnaryNode(AstNode.Op.NOT, parseUnary());
            case PLUS:
                advance();
                return parseUnary();
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
            case TRUE:
                advance();
                return new AstNode.LiteralNode(Value.bool(true));
            case FALSE:
                advance();
                return new AstNode.LiteralNode(Value.bool(false));
            case BLANK:
                advance();
                return new AstNode.LiteralNode(Value.blank());
            case IDENT:
                return parseIdentifier();
            case LPAREN:
                advance();
                AstNode grouped = parseOr();
                if (peek().type() != Type.RPAREN) {
                    throw unexpected(peek());
                }
                advance();
                return grouped;
            default:
                throw unexpected(token);
        }
    }

    /** A whitelisted variable reference or a registry-validated function call. */
    private AstNode parseIdentifier() {
        Token nameToken = advance();
        if (peek().type() != Type.LPAREN) {
            String name = nameToken.text();
            if (!VARIABLES.contains(name)) {
                throw new BusinessException(RuleErrorCode.UNKNOWN_VARIABLE, name);
            }
            return new AstNode.VarNode(name);
        }
        return parseCall(nameToken);
    }

    private AstNode parseCall(Token nameToken) {
        String code = nameToken.text();
        advance(); // LPAREN
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

    private AstNode binary(AstNode.Op op, AstNode left) {
        advance();
        return new AstNode.BinaryNode(op, left, parseAdditive());
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
