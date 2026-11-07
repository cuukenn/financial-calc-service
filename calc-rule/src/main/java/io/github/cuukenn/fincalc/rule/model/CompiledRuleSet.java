package io.github.cuukenn.fincalc.rule.model;

import io.github.cuukenn.fincalc.domain.model.RuleOutcome;
import io.github.cuukenn.fincalc.domain.port.RuleSetView;
import io.github.cuukenn.fincalc.rule.expression.EvalContext;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A set of compiled rules resolving one period at a time, published to the domain through
 * {@link RuleSetView}.
 *
 * <p>House rule R53: rows are kept in canonical {@code (priority, ruleId)} order and
 * {@link #resolve} picks the lowest-{@code priority} row whose matcher hits the period.
 * Task 23 compiled range-only rules; Task 26 attaches the rows' parsed condition expressions via
 * {@link CompiledRule#withCondition(Predicate)} and folds them into resolution through
 * {@link CompiledRule#matches(int, EvalContext)}:
 *
 * <ul>
 *   <li>{@link #resolve(int, BigDecimal, int, BigDecimal)} carries no {@link EvalContext}, so it
 *       cannot evaluate a condition — and it must not guess one. A rule with an attached
 *       condition is therefore <em>skipped</em> by this overload: no silent wrong match. Use
 *       {@link #resolve(int, BigDecimal, int, BigDecimal, EvalContext)} to evaluate conditions.</li>
 *   <li>The context-accepting overload evaluates range AND condition via
 *       {@link CompiledRule#matches(int, EvalContext)}.</li>
 * </ul>
 *
 * <p>House rule R54: the matched row's {@link RateBase} crosses into the domain as its
 * {@link RateBase#name()}; a row that leaves the base open yields {@code null}, meaning
 * "inherit the built-in behaviour".
 *
 * <p>House rule R55 and the Task 26 carry-forwards: {@link #ruleSetHash()} is a SHA-256
 * fingerprint over the semantic columns only (see {@link #canonicalRow}), in
 * {@code (priority, ruleId)} order, with the injected function registry's digest folded in as the
 * first canonical line (see {@link #functionRegistryHash()}). Presentation and provenance columns
 * ({@code memo}, {@code rowNumber}, {@code cellRef}) and the readability-only {@code stage} are
 * excluded.
 */
public final class CompiledRuleSet implements RuleSetView {

    private final List<CompiledRule> rules;
    private final String functionRegistryHash;
    private final FunctionRegistry functionRegistry;
    private final String hash;

    /**
     * Compiles the rows into a rule set without a function registry.
     *
     * <p>Task 23's constructor, kept for compatibility: every rule comes out unconditional, and
     * {@link #functionRegistryHash()} is the empty string because no registry was injected. New
     * code (the rule compiler) should use
     * {@link #CompiledRuleSet(List, String)} with pre-compiled, conditioned rules.
     *
     * @param rows the rule rows, in any order; never {@code null}, may be empty
     */
    public CompiledRuleSet(List<RuleRow> rows) {
        this(unconditionalRules(rows), "");
    }

    /**
     * Compiles already-built rules into a rule set (Task 26).
     *
     * @param rules                the compiled rules, in any order; the set re-sorts them into
     *                             canonical {@code (priority, ruleId)} order; never {@code null}
     * @param functionRegistryHash the digest of the function registry the rules' conditions were
     *                             parsed against, folded into {@link #ruleSetHash()}; never
     *                             {@code null} (use the empty string for "no registry")
     */
    public CompiledRuleSet(List<CompiledRule> rules, String functionRegistryHash) {
        this(rules, functionRegistryHash, FunctionRegistry.empty());
    }

    /**
     * Compiles already-built rules into a rule set carrying the registry their conditions were
     * parsed against (final-review fix wave): the registry rides along so the engine layer can
     * build the per-period {@link EvalContext} for
     * {@link #resolve(int, BigDecimal, int, BigDecimal, EvalContext)} without re-threading it
     * through the {@code RuleSetView} port.
     *
     * @param rules                the compiled rules, in any order; the set re-sorts them into
     *                             canonical {@code (priority, ruleId)} order; never {@code null}
     * @param functionRegistryHash the digest of {@code functionRegistry}, folded into
     *                             {@link #ruleSetHash()}; never {@code null} (use the empty
     *                             string for "no registry")
     * @param functionRegistry     the registry condition expressions evaluate against; never
     *                             {@code null} (use {@link FunctionRegistry#empty()} for "no
     *                             registry")
     */
    public CompiledRuleSet(List<CompiledRule> rules, String functionRegistryHash,
            FunctionRegistry functionRegistry) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(functionRegistryHash, "functionRegistryHash");
        Objects.requireNonNull(functionRegistry, "functionRegistry");
        List<CompiledRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator
                .comparingInt((CompiledRule rule) -> rule.row().priority())
                .thenComparing(rule -> rule.row().ruleId()));
        this.rules = Collections.unmodifiableList(sorted);
        this.functionRegistryHash = functionRegistryHash;
        this.functionRegistry = functionRegistry;
        StringBuilder canonical = new StringBuilder(functionRegistryHash);
        for (CompiledRule rule : sorted) {
            canonical.append('\n');
            canonical.append(canonicalRow(rule.row()));
        }
        this.hash = sha256Hex(canonical.toString());
    }

    private static List<CompiledRule> unconditionalRules(List<RuleRow> rows) {
        Objects.requireNonNull(rows, "rows");
        List<CompiledRule> compiled = new ArrayList<>(rows.size());
        for (RuleRow row : rows) {
            compiled.add(new CompiledRule(row));
        }
        return compiled;
    }

    /**
     * Resolves the rule governing one period: the lowest-{@code priority} row whose period range
     * covers {@code periodNo} (R53).
     *
     * <p>Task 26 semantics: this overload has no {@link EvalContext}, so a rule carrying a parsed
     * condition cannot be evaluated — and it must not silently match on its range alone. Such
     * rules are skipped here; only unconditional rules can match. Use
     * {@link #resolve(int, BigDecimal, int, BigDecimal, EvalContext)} for the full match.
     *
     * <p>When the matched row leaves the annual rate open, the outcome falls back to
     * {@code defaultAnnualRate}, per the port's contract; when no rule matches the outcome is
     * {@link RuleOutcome#none()}.
     *
     * @param periodNo          one-based period number
     * @param openingBalance    principal balance at the start of the period; reserved for
     *                          condition evaluation in the context-accepting overload
     * @param days              accrual days of the period; reserved for condition evaluation in
     *                          the context-accepting overload
     * @param defaultAnnualRate rate to fall back to when the matched rule leaves the rate open
     * @return the matched rule's outcome, or {@link RuleOutcome#none()} when no unconditional
     *         rule covers the period
     */
    @Override
    public RuleOutcome resolve(int periodNo, BigDecimal openingBalance, int days,
            BigDecimal defaultAnnualRate) {
        for (CompiledRule rule : rules) {
            if (rule.condition() != null || !rule.matches(periodNo)) {
                continue;
            }
            return outcome(rule.row(), defaultAnnualRate);
        }
        return RuleOutcome.none();
    }

    /**
     * Resolves the rule governing one period with condition evaluation (Task 26): the
     * lowest-{@code priority} rule whose range covers {@code periodNo} AND whose parsed condition
     * holds for the given {@code context}.
     *
     * @param periodNo          one-based period number
     * @param openingBalance    principal balance at the start of the period, exposed to
     *                          conditions as {@code BALANCE}
     * @param days              accrual days of the period, exposed to conditions as {@code DAYS}
     * @param defaultAnnualRate rate to fall back to when the matched rule leaves the rate open
     * @param context           the period's variables and function registry; never {@code null}
     * @return the matched rule's outcome, or {@link RuleOutcome#none()} when no rule matches
     */
    public RuleOutcome resolve(int periodNo, BigDecimal openingBalance, int days,
            BigDecimal defaultAnnualRate, EvalContext context) {
        for (CompiledRule rule : rules) {
            if (!rule.matches(periodNo, context)) {
                continue;
            }
            return outcome(rule.row(), defaultAnnualRate);
        }
        return RuleOutcome.none();
    }

    private static RuleOutcome outcome(RuleRow row, BigDecimal defaultAnnualRate) {
        return new RuleOutcome(true, row.ruleId(),
                row.annualRate() != null ? row.annualRate() : defaultAnnualRate,
                row.rateBase() == null ? null : row.rateBase().name(),
                row.principalPlan(), row.principalValue(), row.principalSequence(),
                row.interestTreatment(), row.specialAction(), row.actionParam());
    }

    /**
     * The compiled rules in canonical {@code (priority, ruleId)} order; immutable.
     *
     * <p>Rules compiled by Task 26 may carry a parsed condition ({@code condition() != null});
     * the ordering never depends on it.
     */
    public List<CompiledRule> rules() {
        return rules;
    }

    /**
     * The digest of the function registry the rules' condition expressions were parsed against
     * (Task 26 carry-forward): the registry's own {@code hash()}, or the empty string for sets
     * built without a registry (the Task 23 constructor).
     *
     * @return 64 lowercase hex digits, or the empty string when no registry was injected
     */
    public String functionRegistryHash() {
        return functionRegistryHash;
    }

    /**
     * The function registry the rules' condition expressions were parsed against (final-review
     * fix wave): the registry this set was built with via the three-argument constructor, or the
     * {@link FunctionRegistry#empty() empty registry} for sets built without one. The context
     * builder in the engine layer hands it to the {@link EvalContext} so conditions can call
     * functions.
     *
     * @return the condition-time function registry; never {@code null}
     */
    public FunctionRegistry functionRegistry() {
        return functionRegistry;
    }

    /**
     * The rule set's semantic fingerprint (R55, Task 26 carry-forwards): 64 lowercase hex digits,
     * SHA-256 over the canonical form — the function-registry digest on the first line, then one
     * {@link #canonicalRow} per rule in {@code (priority, ruleId)} order, rows newline-separated.
     * Each row line carries the 13 semantic columns <em>plus the row's raw {@code condition}
     * source as a 14th field</em>, so two sets differing only in an unparsed condition hash
     * differently, and the registry digest makes a function-library change auditable. Stable
     * across rebuilds of the same rows.
     *
     * @return the 64-character lowercase hex digest
     */
    public String ruleSetHash() {
        return hash;
    }

    /**
     * The row's canonical hash input (R55, Task 26): exactly the semantic columns plus the raw
     * condition source, {@code |}-separated, {@code null} rendered as the empty string —
     * {@code ruleId, priority, fromPeriod, toPeriod, annualRate, rateBase, principalPlan,
     * principalValue, principalSequence, interestTreatment, paymentTiming, specialAction,
     * actionParam, condition}. {@code memo}, {@code rowNumber}, {@code cellRef}
     * (presentation/provenance) and {@code stage} (readability only) never enter.
     *
     * <p>Carry-forward 2: the four free-text fields ({@code ruleId}, {@code principalSequence},
     * {@code actionParam}, {@code condition}) are escaped — {@code \} becomes {@code \\} and
     * {@code |} becomes {@code \|} — so a pipe typed into free text cannot shift the field
     * boundaries of the canonical form. The escape is applied before joining and documented here
     * as the canonicalization; the compiler does not reject pipes in free text.
     */
    private static String canonicalRow(RuleRow row) {
        return String.join("|",
                escape(row.ruleId()),
                String.valueOf(row.priority()),
                String.valueOf(row.fromPeriod()),
                String.valueOf(row.toPeriod()),
                plain(row.annualRate()),
                row.rateBase() == null ? "" : row.rateBase().name(),
                row.principalPlan().name(),
                plain(row.principalValue()),
                escape(orEmpty(row.principalSequence())),
                row.interestTreatment().name(),
                row.paymentTiming().name(),
                row.specialAction().name(),
                escape(orEmpty(row.actionParam())),
                escape(orEmpty(row.condition())));
    }

    /** Escapes the canonical field separators in free text: {@code \} then {@code |}. */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|");
    }

    /** Amount in its canonical plain-string form, or the empty string when the row leaves it open. */
    private static String plain(BigDecimal amount) {
        return amount == null ? "" : amount.toPlainString();
    }

    /** The text itself, or the empty string when {@code null}. */
    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /** SHA-256 of the canonical text as 64 lowercase hex digits. */
    private static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
