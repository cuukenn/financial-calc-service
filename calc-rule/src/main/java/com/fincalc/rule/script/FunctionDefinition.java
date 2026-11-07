package com.fincalc.rule.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One {@code DEFINE} entry of an external function script (design §8.6, Task 27): the pure
 * <em>source</em> view of a definition — upper-cased name, formal parameters, the raw body
 * expression source, and where the line came from — before the
 * {@link com.fincalc.rule.function.FunctionRegistry#withDefinitions} pipeline compiles, validates
 * and inlines it.
 *
 * <p>Disclosed deviation from the brief's sketch {@code (name, parameters, Expression body,
 * sourceName, line)}: the body is carried as its source string rather than a compiled
 * {@code Expression}, because forward references and cross-file overrides mean a definition can
 * only be compiled once the <em>full</em> definition set is known — which is exactly the brief's
 * own Step 3 assignment for {@code withDefinitions} ("按拓扑序把每个定义编译为 Expression").
 * The source string also feeds the definition-aware registry hash.
 *
 * <p>Instances are immutable. Names and parameter names are normalized to upper case here, the
 * engine-wide case-insensitivity rule.
 */
public final class FunctionDefinition {

    private final String name;
    private final List<String> parameters;
    private final String bodySource;
    private final String sourceName;
    private final int line;
    private final boolean override;

    /**
     * Creates a definition.
     *
     * @param name        the defined function's name, e.g. {@code 含税金额}; normalized to upper
     *                    case; never {@code null}
     * @param parameters  formal parameter names in source order; normalized to upper case; never
     *                    {@code null} (may be empty)
     * @param bodySource  the expression source after the {@code =}, trimmed; never {@code null}
     * @param sourceName  the {@code .fcs} source the line came from (file name or classpath
     *                    resource name), used in error messages; never {@code null}
     * @param line        1-based line number in the source, for {@code 文件:行号} errors
     * @param override    whether the line carried the {@code OVERRIDE} keyword, licensing the
     *                    replacement of a built-in function
     */
    public FunctionDefinition(String name, List<String> parameters, String bodySource,
            String sourceName, int line, boolean override) {
        this.name = Objects.requireNonNull(name, "name").toUpperCase(Locale.ROOT);
        List<String> normalized = new ArrayList<>(parameters.size());
        for (String parameter : Objects.requireNonNull(parameters, "parameters")) {
            normalized.add(Objects.requireNonNull(parameter, "parameter").toUpperCase(Locale.ROOT));
        }
        this.parameters = List.copyOf(normalized);
        this.bodySource = Objects.requireNonNull(bodySource, "bodySource").trim();
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.line = line;
        this.override = override;
    }

    /** The defined function's name, upper-cased; the registry code it registers under. */
    public String name() {
        return name;
    }

    /** The formal parameter names, upper-cased, in source order; immutable. */
    public List<String> parameters() {
        return parameters;
    }

    /** The body expression source, trimmed, as written after the {@code =}. */
    public String bodySource() {
        return bodySource;
    }

    /** The {@code .fcs} source this line came from (file name or resource name). */
    public String sourceName() {
        return sourceName;
    }

    /** The 1-based line number in {@link #sourceName()}. */
    public int line() {
        return line;
    }

    /** Whether the line carried the {@code OVERRIDE} keyword. */
    public boolean overridesBuiltin() {
        return override;
    }
}
