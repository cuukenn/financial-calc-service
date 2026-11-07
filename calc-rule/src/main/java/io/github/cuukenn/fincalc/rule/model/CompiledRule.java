package io.github.cuukenn.fincalc.rule.model;

import io.github.cuukenn.fincalc.rule.expression.EvalContext;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One compiled rule row: the immutable {@link RuleRow} plus its compiled matcher.
 *
 * <p>House rule R53: Task 26 owns expression compilation, so this task cannot build the brief's
 * {@code CompiledRule(RuleRow, Predicate<EvalContext> condition)} constructor yet. Instead the
 * rule holds a compiled <em>period-range matcher</em> — {@link #matches(int)} over the row's
 * {@code [fromPeriod, toPeriod]} — and Task 26 attaches the parsed condition later, without
 * breaking the existing constructor, via {@link #withCondition(Predicate)}: the returned rule's
 * full match is the range matcher AND the condition, evaluated by {@link #matches(int, EvalContext)}.
 *
 * <p>Instances are immutable: {@code withCondition} returns a new rule and never mutates the
 * receiver, so a compiled set stays stable while Task 26 enriches individual rules.
 */
public final class CompiledRule {

    private final RuleRow row;
    private final Predicate<EvalContext> condition;

    /**
     * Compiles a rule whose matcher is the row's period range only.
     *
     * @param row the source row; never {@code null}
     */
    public CompiledRule(RuleRow row) {
        this.row = Objects.requireNonNull(row, "row");
        this.condition = null;
    }

    private CompiledRule(RuleRow row, Predicate<EvalContext> condition) {
        this.row = row;
        this.condition = condition;
    }

    /** The source row; never {@code null}. */
    public RuleRow row() {
        return row;
    }

    /**
     * The parsed condition attached by {@link #withCondition}, or {@code null} while the rule is
     * unconditional (the state this task leaves every rule in; Task 26 fills it).
     */
    public Predicate<EvalContext> condition() {
        return condition;
    }

    /**
     * The compiled period-range matcher (R53): whether {@code periodNo} falls inside the row's
     * {@code [fromPeriod, toPeriod]}. Range-only by definition — an attached condition is not
     * consulted here because this overload carries no {@link EvalContext}; use
     * {@link #matches(int, EvalContext)} for the full match.
     *
     * @param periodNo one-based period number
     */
    public boolean matches(int periodNo) {
        return periodNo >= row.fromPeriod() && periodNo <= row.toPeriod();
    }

    /**
     * The full match: the period-range matcher AND the attached condition (R53). An unconditional
     * rule matches on the range alone.
     *
     * @param periodNo one-based period number
     * @param context  the period's variables, handed to the condition predicate; never {@code null}
     */
    public boolean matches(int periodNo, EvalContext context) {
        Objects.requireNonNull(context, "context");
        return matches(periodNo) && (condition == null || condition.test(context));
    }

    /**
     * Returns a rule carrying the parsed condition (R53), whose full match is the range matcher
     * AND the condition. The receiver is left untouched.
     *
     * @param condition the parsed condition; never {@code null}
     * @return a new rule with the same row and the condition attached
     */
    public CompiledRule withCondition(Predicate<EvalContext> condition) {
        return new CompiledRule(row, Objects.requireNonNull(condition, "condition"));
    }
}
