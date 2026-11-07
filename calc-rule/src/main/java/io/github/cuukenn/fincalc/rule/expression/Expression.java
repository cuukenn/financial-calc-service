package io.github.cuukenn.fincalc.rule.expression;

import io.github.cuukenn.fincalc.common.error.BusinessException;

/**
 * A compiled rule expression: parse once, evaluate many times against different
 * {@link EvalContext}s.
 *
 * <p>Implementations are immutable and stateless per evaluation — every {@code evaluate} call
 * recomputes from the given context, so a compiled expression is safe to share across periods and
 * threads.
 */
@FunctionalInterface
public interface Expression {

    /**
     * Evaluates the expression.
     *
     * @param context the period's variables and function registry
     * @return the result; a failed computation returns an {@link ValueType#ERROR ERROR} value
     *         (never a thrown exception), so {@code IFERROR} can catch it downstream
     */
    Value evaluate(EvalContext context);

    /**
     * Evaluates the expression as a boolean condition.
     *
     * <p>House rule R57 documentation for the thrown code: an {@link ValueType#ERROR ERROR}
     * result cannot be read as a condition, so this raises a
     * {@link BusinessException} whose code is the error value's own design-assigned
     * {@link FormulaError} code ({@code FCS-RULE-3005..3009}, design §8.5). No separate
     * {@code RuleErrorCode} constant is added for this path — the error value already carries the
     * stable code, and R57 forbids codes without a distinct raising check.
     *
     * @param context the period's variables and function registry
     * @return the boolean result of the expression
     * @throws BusinessException when the evaluation yields an {@code ERROR} value (carrying that
     *                           value's {@link FormulaError}) or when a text operand cannot be
     *                           read as a boolean
     */
    default boolean test(EvalContext context) {
        Value result = evaluate(context);
        if (result.type() == ValueType.ERROR) {
            throw new BusinessException((FormulaError) result.raw());
        }
        return result.asBoolean();
    }
}
