package com.fincalc.rule.function;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Case-insensitive registry of the {@link FormulaFunction}s an expression may call.
 *
 * <p>Skeleton by design: the expression engine only needs {@link #empty()},
 * {@link #register(FormulaFunction)}, {@link #find(String)} and {@link #codes()}; the built-in
 * function library arrives in a later task and registers into the same skeleton.
 *
 * <p>Instances are immutable — {@link #register} returns a new registry — so a compiled
 * {@link com.fincalc.rule.expression.Expression} never observes a registry changing underneath it.
 * Registering a function whose code is already present replaces it, letting a caller override a
 * default.
 */
public final class FunctionRegistry {

    private static final FunctionRegistry EMPTY = new FunctionRegistry(Map.of());

    private final Map<String, FormulaFunction> byCode;

    private FunctionRegistry(Map<String, FormulaFunction> byCode) {
        this.byCode = byCode;
    }

    /** A registry with no functions. */
    public static FunctionRegistry empty() {
        return EMPTY;
    }

    /**
     * Registers a function under its {@link FormulaFunction#code()}, replacing one already
     * registered under the same code.
     *
     * @param function the function; never {@code null}, must expose a non-null code
     * @return a new registry carrying the function
     */
    public FunctionRegistry register(FormulaFunction function) {
        Objects.requireNonNull(function, "function");
        String code = Objects.requireNonNull(function.code(), "function.code()");
        Map<String, FormulaFunction> next = new LinkedHashMap<>(byCode);
        next.put(code.toUpperCase(Locale.ROOT), function);
        return new FunctionRegistry(Collections.unmodifiableMap(next));
    }

    /**
     * The function registered under {@code code}.
     *
     * @param code call name, matched case-insensitively
     * @return the registered function, or {@code null} when absent
     */
    public FormulaFunction find(String code) {
        if (code == null) {
            return null;
        }
        return byCode.get(code.toUpperCase(Locale.ROOT));
    }

    /** Every registered code, sorted alphabetically; immutable snapshot. */
    public List<String> codes() {
        List<String> sorted = new ArrayList<>(byCode.keySet());
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }
}
