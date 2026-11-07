package com.fincalc.rule.function;

import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.Expression;
import com.fincalc.rule.expression.Value;
import java.util.List;

/**
 * A {@link FormulaFunction} that receives its arguments as <em>unevaluated</em>
 * {@link Expression} nodes, so it decides which of them to evaluate (design §8.5, brief Task 25
 * Step 3): {@code IF} evaluates only the branch its condition selects, {@code IFS} only the first
 * matching pair, {@code SWITCH} only the hit arm, and {@code IFERROR}/{@code IFNA} the fallback
 * only when the primary argument failed.
 *
 * <p>{@link com.fincalc.rule.expression.AstNode.CallNode} routes a resolved
 * {@code LazyFunction}'s argument nodes to {@link #invokeLazy} untouched; every other function
 * keeps the eager path. Arity is still validated at parse time against the inherited
 * {@link #minArity()}/{@link #maxArity()}, and the registry still resolves the function
 * case-insensitively — laziness changes nothing about lookup or validation.
 *
 * <p>Implementations must stay pure (no I/O, clock or randomness) and return {@code ERROR}
 * values rather than throwing, exactly like eager functions.
 */
public interface LazyFunction extends FormulaFunction {

    /**
     * Runs the function over unevaluated argument nodes.
     *
     * @param args    the call's argument expressions, in source order, in
     *                {@code [minArity, maxArity]} count (unbounded when {@link #maxArity()} is
     *                {@code -1}); each is a reusable compiled subtree
     * @param context the evaluation the call belongs to
     * @return the result; a failed computation returns an {@code ERROR} value rather than throwing
     */
    Value invokeLazy(List<Expression> args, EvalContext context);
}
