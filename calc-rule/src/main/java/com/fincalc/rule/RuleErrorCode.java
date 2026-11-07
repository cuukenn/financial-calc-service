package com.fincalc.rule;

import com.fincalc.common.error.ErrorCode;

/**
 * Error codes raised by rule compilation, following the {@code FCS-RULE-<4 digits>} convention.
 *
 * <p>House rule R57: a code enters this enum together with the code path that raises it, and each
 * constant names the check that raises it. Task 24 (the expression engine) raises exactly three
 * checks, all at parse time:
 *
 * <ul>
 *   <li>{@link #UNKNOWN_VARIABLE} — an identifier outside the hardcoded variable whitelist;</li>
 *   <li>{@link #EXPRESSION_SYNTAX_ERROR} — the lexer or parser rejects the expression (including
 *       the {@code .} / {@code new} / assignment escapes the grammar forbids);</li>
 *   <li>{@link #UNKNOWN_FUNCTION} — a call to a function missing from the
 *       {@link com.fincalc.rule.function.FunctionRegistry}, or a call whose argument count falls
 *       outside {@code [minArity, maxArity]}. One code covers both, because the design doc assigns
 *       {@code FCS-RULE-3009} to the function-name check family; the message text distinguishes
 *       the two cases. No separate arity code was invented to avoid colliding with later
 *       assignments.</li>
 * </ul>
 *
 * <p>The Excel error mapping ({@code FCS-RULE-3005..3009}, design §8.5) is not enumerated here:
 * those errors surface as {@link com.fincalc.rule.expression.FormulaError} data carried inside
 * {@code ERROR} values (never thrown by evaluation), so they need no exception code until a task
 * raises them as exceptions. Task 23 (the rule metamodel) added its own code at its raise site:
 * {@link #INVALID_ROW_RANGE}, raised by the {@link com.fincalc.rule.model.RuleRow} constructor's
 * row-range check. Task 26 (the rule compiler) raises its V1–V8 checks: V1/V3 reuse
 * {@link #INVALID_ROW_RANGE}, and the remaining checks each carry their own constant below.
 * Tasks 25/26 add their own codes at their own raise sites.
 */
public enum RuleErrorCode implements ErrorCode {

    /**
     * Row-range check in the {@link com.fincalc.rule.model.RuleRow} constructor (Task 23) and the
     * compiler's V1/V3 coverage checks (Task 26): a rule row must cover {@code fromPeriod >= 1},
     * {@code fromPeriod <= toPeriod} and {@code toPeriod <= element.periods()}, and without
     * {@code allowPartial} the periods {@code 1..element.periods()} must be fully covered.
     */
    INVALID_ROW_RANGE("FCS-RULE-2001",
            "FCS-RULE-2001: 规则行期间区间无效，要求 fromPeriod >= 1 且 fromPeriod <= toPeriod: %s"),

    /**
     * Overlap checks of the rule compiler (Task 26, V2): two rows sharing a
     * {@code priority} must not cover overlapping period ranges, and no two rows may carry the
     * same {@code ruleId} (a duplicate would make {@code (priority, ruleId)} canonical ordering
     * ambiguous). The message text distinguishes the two cases; no separate code was invented
     * because both findings are about the same structural soundness family.
     */
    PRIORITY_INTERVAL_OVERLAP("FCS-RULE-2002",
            "FCS-RULE-2002: 同优先级规则期间区间重叠或规则ID重复: %s"),

    /**
     * Principal-plan total checks of the rule compiler (Task 26, V4/V5/V6): the sum of
     * {@code PERCENT} rows' {@code principalValue} must be 100, the deterministic plan total
     * (FIXED value × periods + CUSTOM sequence sums + PERCENT share) must equal the lease
     * principal when no algorithm-driven row is present, and a {@code CUSTOM} row's
     * {@code principalSequence} must hold exactly one entry per covered period.
     */
    PRINCIPAL_PLAN_TOTAL("FCS-RULE-2003",
            "FCS-RULE-2003: 本金计划合计与本金不一致: %s"),

    /**
     * Special-action whitelist check of the rule compiler (Task 26, V8): v1 supports only
     * {@code NONE}, {@code FEE_AMORTIZE} and {@code DEPOSIT_OFFSET}; the reserved
     * {@code PREPAY}/{@code EXTEND} values are rejected at compile time.
     */
    UNSUPPORTED_SPECIAL_ACTION("FCS-RULE-2005",
            "FCS-RULE-2005: 不支持的一次性动作: %s"),

    /**
     * Annual-rate range check of the rule compiler (Task 26, V7): a row's {@code annualRate} is
     * a fraction and must lie in {@code [0, 1]}.
     */
    ANNUAL_RATE_OUT_OF_RANGE("FCS-RULE-2006",
            "FCS-RULE-2006: 年利率须在 [0,1] 内: %s"),

    /** Parse-time whitelist check: the identifier is not one of the nine period variables. */
    UNKNOWN_VARIABLE("FCS-RULE-3001", "FCS-RULE-3001: 未知变量: %s"),

    /**
     * Lexer/parser check: the expression does not satisfy the §8.4 grammar, or its nesting
     * exceeds the §8.6 depth cap of 64 levels.
     */
    EXPRESSION_SYNTAX_ERROR("FCS-RULE-3004", "FCS-RULE-3004: 表达式语法错误: %s"),

    /**
     * Parse-time registry check: unknown function name, or argument count outside
     * {@code [minArity, maxArity]}.
     */
    UNKNOWN_FUNCTION("FCS-RULE-3009", "FCS-RULE-3009: 未知函数或参数个数不匹配: %s");

    private final String code;
    private final String message;

    RuleErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
