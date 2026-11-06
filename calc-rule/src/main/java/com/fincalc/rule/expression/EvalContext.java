package com.fincalc.rule.expression;

import com.fincalc.rule.function.FunctionRegistry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only inputs of one expression evaluation: the period's variables and the function registry.
 *
 * <p>Variable names are case-insensitive: the constructor normalizes every key to upper case, and
 * {@link #variable(String)} normalizes the lookup the same way, matching the engine-wide rule that
 * identifiers are upper-cased by the lexer.
 *
 * <p>A name missing from the map resolves to {@link Value#blank()} — the spreadsheet's empty-cell
 * semantics. This is unreachable for the nine whitelisted variables when the expression went
 * through {@link ExpressionParser#parse(String, FunctionRegistry)} (unknown identifiers are
 * rejected at parse time), so a blank here means the caller built the context without one of them
 * and the expression reads it as an empty cell.
 */
public final class EvalContext {

    private static final EvalContext EMPTY =
            new EvalContext(Map.of(), FunctionRegistry.empty());

    private final Map<String, Value> variables;
    private final FunctionRegistry functions;

    /**
     * Creates a context.
     *
     * @param variables values keyed by variable name, compared case-insensitively; keys and values
     *                  must be non-null; copied, never retained
     * @param functions the registry functions resolve against at evaluation time
     */
    public EvalContext(Map<String, Value> variables, FunctionRegistry functions) {
        Objects.requireNonNull(functions, "functions");
        Map<String, Value> normalized = new LinkedHashMap<>();
        if (variables != null) {
            for (Map.Entry<String, Value> entry : variables.entrySet()) {
                normalized.put(upper(entry.getKey()), Objects.requireNonNull(entry.getValue(),
                        "variables[" + entry.getKey() + "]"));
            }
        }
        this.variables = Map.copyOf(normalized);
        this.functions = functions;
    }

    /** A context with no variables and an empty function registry. */
    public static EvalContext empty() {
        return EMPTY;
    }

    /**
     * The value of a variable, or {@link Value#blank()} when the context does not carry it (see
     * the class javadoc for why this is a deliberate empty-cell reading, not an error).
     *
     * @param name variable name, compared case-insensitively; never {@code null}
     */
    public Value variable(String name) {
        Objects.requireNonNull(name, "name");
        return variables.getOrDefault(upper(name), Value.blank());
    }

    /** The registry function calls resolve against at evaluation time. */
    public FunctionRegistry functions() {
        return functions;
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
