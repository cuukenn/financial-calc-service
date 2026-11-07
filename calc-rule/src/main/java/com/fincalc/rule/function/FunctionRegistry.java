package com.fincalc.rule.function;

import com.fincalc.common.error.BusinessException;
import com.fincalc.rule.RuleErrorCode;
import com.fincalc.rule.expression.AstNode;
import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.function.datex.DateFunctions;
import com.fincalc.rule.function.financial.FinancialFunctions;
import com.fincalc.rule.function.info.InfoFunctions;
import com.fincalc.rule.function.logical.LogicalFunctions;
import com.fincalc.rule.function.lookup.LookupFunctions;
import com.fincalc.rule.function.math.MathFunctions;
import com.fincalc.rule.function.textx.TextFunctions;
import com.fincalc.rule.script.FunctionDefinition;
import com.fincalc.rule.script.ScriptInliner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * <p>Besides the {@link #empty()} skeleton the expression engine builds on, this class owns the
 * built-in v1 function library (design §8.5): {@link #defaults()} registers the ~80
 * Excel-compatible functions of the seven families (math, financial, date, logical, lookup, text,
 * info) exactly once and the returned instance is frozen — it is a shared immutable snapshot, and
 * {@link #register} on top of it yields a new registry without ever mutating the default.
 *
 * <p>{@link #hash()} fingerprints the registry's function surface: the SHA-256 of the sorted
 * {@code code} list joined by newlines, as 64 lowercase hex characters. The rule compiler carries
 * this digest into the rule-set hash (design §8.2) so a script change is auditable; any code
 * added, removed or renamed changes the digest.
 *
 * <p>Instances are immutable — {@link #register} returns a new registry — so a compiled
 * {@link com.fincalc.rule.expression.Expression} never observes a registry changing underneath it.
 * Registering a function whose code is already present replaces it, letting a caller override a
 * default.
 */
public final class FunctionRegistry {

    private static final FunctionRegistry EMPTY = new FunctionRegistry(Map.of());

    /** The shared, fully populated default library; built once, never mutated. */
    private static final FunctionRegistry DEFAULTS = buildDefaults();

    private final Map<String, FormulaFunction> byCode;

    /** Lazily computed {@link #hash()} memo; safe unsynchronized (deterministic content). */
    private volatile String hash;

    private FunctionRegistry(Map<String, FormulaFunction> byCode) {
        this.byCode = byCode;
    }

    /** A registry with no functions. */
    public static FunctionRegistry empty() {
        return EMPTY;
    }

    /**
     * The built-in v1 function library: every family's {@code register} applied once over the
     * empty registry. No reflection — each family lists its functions explicitly.
     *
     * @return the shared frozen default registry; the same instance on every call
     */
    public static FunctionRegistry defaults() {
        return DEFAULTS;
    }

    private static FunctionRegistry buildDefaults() {
        FunctionRegistry registry = empty();
        registry = MathFunctions.register(registry);
        registry = FinancialFunctions.register(registry);
        registry = DateFunctions.register(registry);
        registry = LogicalFunctions.register(registry);
        registry = LookupFunctions.register(registry);
        registry = TextFunctions.register(registry);
        registry = InfoFunctions.register(registry);
        return registry;
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

    /**
     * Compiles external function script definitions onto this registry (design §8.6, Task 27),
     * returning a new frozen registry.
     *
     * <p>Pipeline: (1) definitions are keyed by upper-cased name, a later definition replacing
     * an earlier one; (2) a definition whose name collides with a <em>non-script</em> function
     * already present raises {@code FCS-RULE-3010} unless its line carried the {@code OVERRIDE}
     * keyword — replacing a script function from an earlier load is always allowed (that is the
     * loader's later-file-wins merge rule); (3) arity-exact placeholder functions make every
     * defined name resolvable, so all bodies compile in one scope with forward references
     * (parse-time codes 3001/3004/3009, each attributed to the definition's {@code 文件:行号});
     * (4) {@link ScriptInliner} DFS-checks the call graph ({@code FCS-RULE-3011} on any direct
     * or indirect recursion), expands every call to a script function into the callee's body
     * with formal parameters substituted by the argument subtrees, and re-checks the §8.6 depth
     * cap of 64 on the expanded AST (R58); (5) each expanded body registers as a
     * {@link ScriptInliner.ScriptFunction} — eager invoke over the inlined AST, no runtime
     * script-call overhead. Bodies may also call script functions from an earlier
     * {@code withDefinitions} on the same base; those stay runtime calls of the already-inlined
     * {@code ScriptFunction}.
     *
     * @param definitions the script definitions to add; never {@code null}; an empty list
     *                    returns {@code this}
     * @return a new frozen registry carrying the inlined script functions
     */
    public FunctionRegistry withDefinitions(List<FunctionDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        if (definitions.isEmpty()) {
            return this;
        }
        Map<String, FunctionDefinition> byName = new LinkedHashMap<>();
        for (FunctionDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definitions element");
            byName.put(definition.name(), definition);
        }
        for (FunctionDefinition definition : byName.values()) {
            FormulaFunction existing = byCode.get(definition.name());
            if (existing != null && !(existing instanceof ScriptInliner.ScriptFunction)
                    && !definition.overridesBuiltin()) {
                throw new BusinessException(RuleErrorCode.SCRIPT_OVERRIDE_BUILTIN,
                        definition.name() + " (" + definition.sourceName() + " 第 "
                                + definition.line() + " 行)");
            }
        }
        FunctionRegistry scope = this;
        for (FunctionDefinition definition : byName.values()) {
            scope = scope.register(new PlaceholderFunction(definition));
        }
        Map<String, AstNode> bodies = ScriptInliner.compileBodies(byName, scope);
        Map<String, AstNode> inlined = ScriptInliner.inline(byName, bodies);
        FunctionRegistry next = this;
        for (FunctionDefinition definition : byName.values()) {
            next = next.register(new ScriptInliner.ScriptFunction(definition.name(),
                    definition.parameters(), inlined.get(definition.name()),
                    definition.bodySource()));
        }
        return next;
    }

    /** Every registered code, sorted alphabetically; immutable snapshot. */
    public List<String> codes() {
        List<String> sorted = new ArrayList<>(byCode.keySet());
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }

    /**
     * The stable fingerprint of this registry's function surface (design §8.6: 所有定义按名称排序
     * 后的 SHA-256), rendered as 64 lowercase hex characters. Computed once per instance.
     *
     * <p>Canonical input, extended in Task 27 so script definition content participates: every
     * function contributes one line keyed by its code, sorted by code, joined with {@code '\n'}
     * (UTF-8). A non-script (built-in) function contributes its bare {@code code} — unchanged
     * from the Task 25 format, so the default library's digest is stable across the extension. A
     * script definition (a {@link ScriptInliner.ScriptFunction}) contributes
     * {@code code(param1,param2,...)=bodySource}, so any change to a definition's parameter list
     * or body expression changes the digest even when the code set does not.
     */
    public String hash() {
        if (hash == null) {
            StringBuilder canonical = new StringBuilder();
            for (String code : codes()) {
                if (canonical.length() > 0) {
                    canonical.append('\n');
                }
                canonical.append(code);
                FormulaFunction function = byCode.get(code);
                if (function instanceof ScriptInliner.ScriptFunction) {
                    ScriptInliner.ScriptFunction script = (ScriptInliner.ScriptFunction) function;
                    canonical.append('(')
                            .append(String.join(",", script.parameters()))
                            .append(')')
                            .append('=')
                            .append(script.bodySource());
                }
            }
            hash = sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
        }
        return hash;
    }

    private static String sha256Hex(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload);
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated for every JRE; this cannot happen.
            throw new IllegalStateException("SHA-256 digest unavailable", ex);
        }
    }

    /**
     * Parse-time stand-in registered for every script-defined name so definition bodies compile
     * with forward references (existence + exact-arity validation at parse time). Never
     * evaluated: every call to a script function is inlined away before any compiled artifact
     * becomes observable through {@link #withDefinitions}.
     */
    private static final class PlaceholderFunction implements FormulaFunction {

        private final String code;
        private final int arity;

        PlaceholderFunction(FunctionDefinition definition) {
            this.code = definition.name();
            this.arity = definition.parameters().size();
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public int minArity() {
            return arity;
        }

        @Override
        public int maxArity() {
            return arity;
        }

        @Override
        public Value invoke(List<Value> args, EvalContext context) {
            throw new UnsupportedOperationException("占位函数仅在编译期用于解析脚本引用: " + code);
        }
    }
}
