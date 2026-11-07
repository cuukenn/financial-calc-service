package io.github.cuukenn.fincalc.rule.compiler;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.rule.expression.EvalContext;
import io.github.cuukenn.fincalc.rule.expression.Expression;
import io.github.cuukenn.fincalc.rule.expression.ExpressionParser;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import io.github.cuukenn.fincalc.rule.model.CompiledRule;
import io.github.cuukenn.fincalc.rule.model.CompiledRuleSet;
import io.github.cuukenn.fincalc.rule.model.RuleRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiles {@link RuleRow}s into a validated, expression-attached {@link CompiledRuleSet}
 * (Task 26).
 *
 * <p>{@link #compile} runs in three phases, honouring the aggregate-then-throw discipline:
 *
 * <ol>
 *   <li>{@link RuleSetValidator} checks V1–V8 (including duplicate ruleIds) and collects
 *       every finding;</li>
 *   <li>each row's raw {@code condition} string (null/blank → unconditional) is parsed
 *       <em>at compile time</em> through the constructor-injected {@link FunctionRegistry} via
 *       {@link ExpressionParser#parse(String, FunctionRegistry)}. Disclosed choice: a parse
 *       failure is surfaced as a {@code ERROR}-level {@link ValidationIssue} carrying the
 *       parser's own stable code ({@code FCS-RULE-3001} unknown variable, {@code FCS-RULE-3004}
 *       syntax, {@code FCS-RULE-3009} unknown function/arity) instead of letting the
 *       {@code BusinessException} propagate — that keeps the collect-all-then-throw contract and
 *       still satisfies the brief's requirement that an unknown function fails compilation with
 *       3009, never at runtime;</li>
 *   <li>when any {@code ERROR} issue exists the compilation throws
 *       {@link RuleValidationException} exactly once; otherwise the rows are assembled into
 *       conditioned {@link CompiledRule}s (range matcher AND parsed condition via
 *       {@link CompiledRule#withCondition}) and the set carries the registry's {@code hash()} as
 *       its {@link CompiledRuleSet#functionRegistryHash()} so the digest lands in
 *       {@code ruleSetHash()}.</li>
 * </ol>
 *
 * <p>Condition semantics under the context-less resolve are pinned by
 * {@link CompiledRuleSet#resolve(int, java.math.BigDecimal, int, java.math.BigDecimal)}: a
 * conditioned rule never matches without an {@link EvalContext} — no silent wrong match. Use
 * {@link CompiledRuleSet#resolve(int, java.math.BigDecimal, int, java.math.BigDecimal,
 * EvalContext)} to evaluate conditions.
 *
 * <p>Instances are immutable and safe to share.
 */
public final class RuleCompiler {

    private final FunctionRegistry functions;
    private final RuleSetValidator validator;

    /**
     * Creates a compiler over the given function registry; every condition expression parses
     * against it, and its {@code hash()} fingerprints the compiled set.
     *
     * @param functions the function library conditions may call; never {@code null}
     */
    public RuleCompiler(FunctionRegistry functions) {
        this.functions = Objects.requireNonNull(functions, "functions");
        this.validator = new RuleSetValidator();
    }

    /**
     * Validates and compiles the rows.
     *
     * @param rows         the rule rows, in any order; never {@code null}, may be empty
     * @param element      the lease element the rows must fit (period bound, principal); never
     *                     {@code null}
     * @param allowPartial whether gaps in the {@code 1..element.periods()} coverage are legal;
     *                     with {@code true} a gap resolves to {@code matched() == false}
     * @return the compiled, validated rule set; rules in canonical {@code (priority, ruleId)}
     *         order
     * @throws RuleValidationException carrying <em>all</em> collected issues when at least one
     *                                 {@code ERROR}-level finding exists (V1–V8, duplicate
     *                                 ruleIds, condition parse failures)
     */
    public CompiledRuleSet compile(List<RuleRow> rows, LeaseElement element, boolean allowPartial) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(element, "element");
        List<ValidationIssue> issues =
                new ArrayList<>(validator.validate(rows, element, allowPartial));

        List<Expression> parsedConditions = new ArrayList<>(rows.size());
        for (RuleRow row : rows) {
            String condition = row.condition();
            if (condition == null || condition.trim().isEmpty()) {
                parsedConditions.add(null);
                continue;
            }
            try {
                parsedConditions.add(ExpressionParser.parse(condition, functions));
            } catch (BusinessException e) {
                issues.add(new ValidationIssue(IssueLevel.ERROR, e.errorCode().code(),
                        "规则 " + row.ruleId() + " 条件表达式编译失败: " + e.getMessage(), null));
                parsedConditions.add(null);
            }
        }

        if (!issues.isEmpty()) {
            throw new RuleValidationException(issues);
        }

        List<CompiledRule> compiled = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            CompiledRule rule = new CompiledRule(rows.get(i));
            Expression condition = parsedConditions.get(i);
            if (condition != null) {
                rule = rule.withCondition(condition::test);
            }
            compiled.add(rule);
        }
        return new CompiledRuleSet(compiled, functions.hash(), functions);
    }
}
