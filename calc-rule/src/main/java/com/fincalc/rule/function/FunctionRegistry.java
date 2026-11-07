package com.fincalc.rule.function;

import com.fincalc.rule.function.datex.DateFunctions;
import com.fincalc.rule.function.financial.FinancialFunctions;
import com.fincalc.rule.function.info.InfoFunctions;
import com.fincalc.rule.function.logical.LogicalFunctions;
import com.fincalc.rule.function.lookup.LookupFunctions;
import com.fincalc.rule.function.math.MathFunctions;
import com.fincalc.rule.function.textx.TextFunctions;
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

    /** Every registered code, sorted alphabetically; immutable snapshot. */
    public List<String> codes() {
        List<String> sorted = new ArrayList<>(byCode.keySet());
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }

    /**
     * The stable fingerprint of this registry's function surface: SHA-256 over the sorted
     * {@code code} list joined with {@code '\n'} (UTF-8), rendered as 64 lowercase hex characters.
     * Computed once per instance.
     */
    public String hash() {
        if (hash == null) {
            hash = sha256Hex(String.join("\n", codes()).getBytes(StandardCharsets.UTF_8));
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
}
