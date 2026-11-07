package io.github.cuukenn.fincalc.rule.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import io.github.cuukenn.fincalc.rule.expression.EvalContext;
import io.github.cuukenn.fincalc.rule.expression.Value;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;

/**
 * Factory for the per-period condition {@link EvalContext} (design §8.2 column D): the nine
 * whitelisted condition variables built from one period's loop state. Extracted (R70) from the
 * {@code RuleDrivenGenerator.PlannedRuleSet} private builder so every consumer that evaluates
 * rule conditions — today the rule-driven generator, tomorrow any caller that needs the
 * context-accepting {@link CompiledRuleSet#resolve(int, BigDecimal, int, BigDecimal,
 * EvalContext)} — shares one variable shape.
 *
 * <p>The variables (parser whitelist, design §8.2 column D): {@code PERIOD} (one-based),
 * {@code BALANCE} (the period's opening balance), {@code DAYS} (the period's accrual days),
 * {@code RATE} (the fallback annual rate — the matched rule's own rate is only known after the
 * condition evaluates, so a condition compares against this default), {@code INDEX}
 * (zero-based), {@code IS_LAST}, {@code PRINCIPAL} (the contract principal),
 * {@code TOTAL_PERIODS} and {@code RESIDUAL} ({@link Value#blank()} when the element carries no
 * residual — a {@code null} cannot enter a {@code Map.copyOf} variable map).
 */
public final class PeriodVariables {

    private PeriodVariables() {
    }

    /**
     * Builds the condition context for one period.
     *
     * @param periodNo           the period's one-based number, at least 1
     * @param openingBalance     the balance the period accrues against; never {@code null}
     * @param days               the period's accrual days
     * @param fallbackAnnualRate the annual rate conditions compare against (the element's
     *                           nominal rate at the call sites); never {@code null}
     * @param totalPeriods       the schedule's total period count
     * @param principal          the contract principal; never {@code null}
     * @param residualValue      the element's residual value, or {@code null} for none
     * @param lastPeriod         whether this is the schedule's final period
     * @param functions          the registry functions resolve against at evaluation time;
     *                           never {@code null}
     * @return an immutable context carrying the nine whitelisted variables
     */
    public static EvalContext evalContext(int periodNo, BigDecimal openingBalance, int days,
            BigDecimal fallbackAnnualRate, int totalPeriods, BigDecimal principal,
            BigDecimal residualValue, boolean lastPeriod, FunctionRegistry functions) {
        Map<String, Value> variables = new HashMap<>();
        variables.put("PERIOD", Value.number(BigDecimal.valueOf(periodNo)));
        variables.put("BALANCE", Value.number(openingBalance));
        variables.put("DAYS", Value.number(BigDecimal.valueOf(days)));
        variables.put("RATE", Value.number(fallbackAnnualRate));
        variables.put("INDEX", Value.number(BigDecimal.valueOf(periodNo - 1)));
        variables.put("IS_LAST", Value.bool(lastPeriod));
        variables.put("PRINCIPAL", Value.number(principal));
        variables.put("TOTAL_PERIODS", Value.number(BigDecimal.valueOf(totalPeriods)));
        variables.put("RESIDUAL", residualValue == null
                ? Value.blank() : Value.number(residualValue));
        return new EvalContext(variables, functions);
    }
}
