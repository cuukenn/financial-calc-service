package com.fincalc.rule.model;

import com.fincalc.domain.model.RuleOutcome;
import com.fincalc.domain.port.RuleSetView;
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
 * {@link #resolve} picks the lowest-{@code priority} row whose {@link CompiledRule#matches(int)}
 * hits the period. This task's rules are unconditional — Task 26 compiles the rows' condition
 * expressions and folds condition evaluation into resolution via
 * {@link CompiledRule#withCondition(Predicate)} / {@link CompiledRule#matches(int, EvalContext)}.
 *
 * <p>House rule R54: the matched row's {@link RateBase} crosses into the domain as its
 * {@link RateBase#name()}; a row that leaves the base open yields {@code null}, meaning
 * "inherit the built-in behaviour".
 *
 * <p>House rule R55: {@link #ruleSetHash()} is a SHA-256 fingerprint over the semantic columns
 * only (see {@link #canonicalRow}), in {@code (priority, ruleId)} order. Presentation and
 * provenance columns ({@code memo}, {@code rowNumber}, {@code cellRef}) and the readability-only
 * {@code stage} are excluded. Task 26 folds {@code functionRegistryHash()} — and the parsed
 * condition it introduces — in later.
 */
public final class CompiledRuleSet implements RuleSetView {

    private final List<CompiledRule> rules;
    private final String hash;

    /**
     * Compiles the rows into a rule set.
     *
     * @param rows the rule rows, in any order; never {@code null}, may be empty
     */
    public CompiledRuleSet(List<RuleRow> rows) {
        Objects.requireNonNull(rows, "rows");
        List<RuleRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(RuleRow::priority).thenComparing(RuleRow::ruleId));
        List<CompiledRule> compiled = new ArrayList<>(sorted.size());
        StringBuilder canonical = new StringBuilder();
        for (RuleRow row : sorted) {
            compiled.add(new CompiledRule(row));
            if (canonical.length() > 0) {
                canonical.append('\n');
            }
            canonical.append(canonicalRow(row));
        }
        this.rules = Collections.unmodifiableList(compiled);
        this.hash = sha256Hex(canonical.toString());
    }

    /**
     * Resolves the rule governing one period: the lowest-{@code priority} row whose period range
     * covers {@code periodNo} (R53).
     *
     * <p>When the matched row leaves the annual rate open, the outcome falls back to
     * {@code defaultAnnualRate}, per the port's contract; when no row covers the period the
     * outcome is {@link RuleOutcome#none()}.
     *
     * @param periodNo          one-based period number
     * @param openingBalance    principal balance at the start of the period; reserved for
     *                          Task 26's condition evaluation
     * @param days              accrual days of the period; reserved for Task 26's condition
     *                          evaluation
     * @param defaultAnnualRate rate to fall back to when the matched rule leaves the rate open
     * @return the matched rule's outcome, or {@link RuleOutcome#none()} when no rule covers
     *         the period
     */
    @Override
    public RuleOutcome resolve(int periodNo, BigDecimal openingBalance, int days,
            BigDecimal defaultAnnualRate) {
        for (CompiledRule rule : rules) {
            if (!rule.matches(periodNo)) {
                continue;
            }
            RuleRow row = rule.row();
            return new RuleOutcome(true, row.ruleId(),
                    row.annualRate() != null ? row.annualRate() : defaultAnnualRate,
                    row.rateBase() == null ? null : row.rateBase().name(),
                    row.principalPlan(), row.principalValue(), row.principalSequence(),
                    row.interestTreatment(), row.specialAction(), row.actionParam());
        }
        return RuleOutcome.none();
    }

    /**
     * The compiled rules in canonical {@code (priority, ruleId)} order; immutable.
     *
     * <p>Every rule this task compiles is unconditional ({@code condition() == null}); Task 26
     * attaches parsed conditions without reordering this view.
     */
    public List<CompiledRule> rules() {
        return rules;
    }

    /**
     * The rule set's semantic fingerprint (R55): 64 lowercase hex digits, SHA-256 over the rows'
     * canonical form — semantic columns {@code |}-separated, rows newline-separated, rows in
     * {@code (priority, ruleId)} order, amounts in {@code toPlainString()}. Stable across
     * rebuilds of the same rows.
     *
     * @return the 64-character lowercase hex digest
     */
    public String ruleSetHash() {
        return hash;
    }

    /**
     * The row's canonical hash input (R55): exactly the semantic columns, {@code |}-separated,
     * {@code null} rendered as the empty string —
     * {@code ruleId, priority, fromPeriod, toPeriod, annualRate, rateBase, principalPlan,
     * principalValue, principalSequence, interestTreatment, paymentTiming, specialAction,
     * actionParam}. {@code memo}, {@code rowNumber}, {@code cellRef} (presentation/provenance)
     * and {@code stage} (readability only) never enter.
     */
    private static String canonicalRow(RuleRow row) {
        return String.join("|",
                row.ruleId(),
                String.valueOf(row.priority()),
                String.valueOf(row.fromPeriod()),
                String.valueOf(row.toPeriod()),
                plain(row.annualRate()),
                row.rateBase() == null ? "" : row.rateBase().name(),
                row.principalPlan().name(),
                plain(row.principalValue()),
                orEmpty(row.principalSequence()),
                row.interestTreatment().name(),
                row.paymentTiming().name(),
                row.specialAction().name(),
                orEmpty(row.actionParam()));
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
