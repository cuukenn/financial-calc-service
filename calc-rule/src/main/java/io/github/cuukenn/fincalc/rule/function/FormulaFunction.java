package io.github.cuukenn.fincalc.rule.function;

import io.github.cuukenn.fincalc.rule.expression.EvalContext;
import io.github.cuukenn.fincalc.rule.expression.Value;
import java.util.List;

/**
 * A spreadsheet-style function an expression can call, e.g. {@code ROUND(x, 2)}.
 *
 * <p>Implementations are registered in a {@link FunctionRegistry} under {@link #code()} (matched
 * case-insensitively) and receive already-evaluated arguments. Arguments arrive verbatim: an
 * {@code ERROR} value is passed through rather than converted, so error-handling functions such as
 * {@code IFERROR} can inspect it while ordinary functions coerce it through
 * {@link Value#asNumber()} and thereby surface the same error.
 */
public interface FormulaFunction {

    /** The call name, e.g. {@code ROUND}; matched case-insensitively. */
    String code();

    /** Smallest accepted argument count. */
    int minArity();

    /**
     * Largest accepted argument count, or {@code -1} for a variadic function with no upper bound.
     */
    int maxArity();

    /**
     * Runs the function.
     *
     * @param args    the evaluated arguments, in source order, in {@code [minArity, maxArity]}
     *                count (unbounded when {@link #maxArity()} is {@code -1})
     * @param context the evaluation the call belongs to, for functions that read variables
     * @return the result; a failed computation returns an {@code ERROR} value rather than throwing
     */
    Value invoke(List<Value> args, EvalContext context);
}
