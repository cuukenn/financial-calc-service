package io.github.cuukenn.fincalc.rule.expression;

import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Entry point of the expression engine: compiles an expression source string into a reusable
 * {@link Expression}.
 *
 * <p>Compilation runs the {@link Lexer} and the recursive-descent {@link Parser}, validating
 * variables against the hardcoded whitelist and function calls against the passed
 * {@link FunctionRegistry}; both failures raise a {@link io.github.cuukenn.fincalc.common.error.BusinessException}
 * at parse time. A blank (or null) source compiles to the constant-true expression — a rule with
 * no condition matches every period.
 */
public final class ExpressionParser {

    private static final Expression ALWAYS_TRUE = context -> Value.bool(true);

    private ExpressionParser() {
    }

    /**
     * Compiles an expression.
     *
     * @param expr      expression source, e.g. {@code PERIOD >= 1 && RATE > 0.05}; blank or
     *                  {@code null} yields the constant-true expression
     * @param functions registry the compiled expression's function calls resolve against at
     *                  evaluation time; parse-time validation uses the same registry
     * @return a compiled, reusable expression
     * @throws io.github.cuukenn.fincalc.common.error.BusinessException with
     *         {@code FCS-RULE-3001} for an identifier outside the whitelist,
     *         {@code FCS-RULE-3004} for a syntax error, or
     *         {@code FCS-RULE-3009} for an unknown function or an argument-count violation
     */
    public static Expression parse(String expr, FunctionRegistry functions) {
        Objects.requireNonNull(functions, "functions");
        if (expr == null || expr.trim().isEmpty()) {
            return ALWAYS_TRUE;
        }
        List<Token> tokens = new Lexer(expr).tokenize();
        AstNode root = new Parser(tokens, functions).parse();
        return root::eval;
    }

    /**
     * Compiles an expression to its raw {@link AstNode} tree (Task 27), additionally accepting
     * {@code additionalVariables} as local variable references — used by the external function
     * script pipeline, whose definition bodies read their formal parameters. All parse-time
     * checks of {@link #parse(String, FunctionRegistry)} apply unchanged (whitelist, registry
     * resolution, arity, depth cap).
     *
     * @param expr                expression source; blank or {@code null} yields the
     *                            {@link Value#blank() BLANK} literal
     * @param functions           registry the body's function calls resolve against (built-ins
     *                            plus placeholders for every script-defined name)
     * @param additionalVariables the formal parameter names of the surrounding definition,
     *                            matched upper-cased; never {@code null}
     * @return the parsed tree, for the script inliner to expand
     */
    public static AstNode parseAst(String expr, FunctionRegistry functions,
            Set<String> additionalVariables) {
        Objects.requireNonNull(functions, "functions");
        Objects.requireNonNull(additionalVariables, "additionalVariables");
        if (expr == null || expr.trim().isEmpty()) {
            return new AstNode.LiteralNode(Value.blank());
        }
        List<Token> tokens = new Lexer(expr).tokenize();
        return new Parser(tokens, functions, additionalVariables).parse();
    }
}
