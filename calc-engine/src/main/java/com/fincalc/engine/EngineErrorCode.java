package com.fincalc.engine;

import com.fincalc.common.error.ErrorCode;

/**
 * Error codes raised by the engine orchestration, following the {@code FCS-ENG-<4 digits>}
 * convention. House rule R57: a code enters this enum together with the code path that raises it,
 * and each constant names the check that raises it.
 *
 * <ul>
 *   <li>{@link #CALCULATION_CONTEXT_INVALID} — raised by
 *       {@link CalculationFacade#calculate} when the lease element's day-count or date-roll code
 *       does not resolve to a built-in convention (orchestration step 1);</li>
 *   <li>{@link #GENERATOR_NOT_REGISTERED} — raised by {@link GeneratorRegistry#require} when no
 *       generator is registered under the requested repayment method (orchestration step 2);</li>
 *   <li>{@link #RULE_DRIVEN_REQUIRES_RULE_SET} — raised by {@link CalculationFacade#calculate}
 *       when {@code method == RULE_DRIVEN} arrives without a compiled rule set (orchestration
 *       step 2);</li>
 *   <li>{@link #FUNCTION_SCRIPT_DIR_LOAD_FAILED} — raised by
 *       {@link CalculationFacade#defaultsWithScripts} when the external script directory cannot be
 *       read; script syntax errors keep the loader's own {@code FCS-RULE-*} codes;</li>
 *   <li>{@link #IRR_ROOT_AMBIGUOUS} — WARN-level {@code ValidationIssue} code raised by
 *       {@link CalculationFacade#calculate} when the event-table flow set changes sign more than
 *       once, so the solved IRR root is ambiguous (ledger ruling R30 posture);</li>
 *   <li>{@link #RULE_FALLBACK_PERIOD} — WARN-level {@code ValidationIssue} code raised by
 *       {@link CalculationFacade#calculate} for each period that fell back to the built-in
 *       behaviour under {@code allowPartialRuleSet}.</li>
 * </ul>
 */
public enum EngineErrorCode implements ErrorCode {

    /**
     * Day-count / date-roll resolution check of {@link CalculationFacade#calculate} (orchestration
     * step 1): the element's convention code must name a built-in convention.
     */
    CALCULATION_CONTEXT_INVALID("FCS-ENG-1001", "FCS-ENG-1001: 计算口径配置缺失: %s"),

    /**
     * Generator lookup check of {@link GeneratorRegistry#require} (orchestration step 2): the
     * element's repayment method must have a registered generator.
     */
    GENERATOR_NOT_REGISTERED("FCS-ENG-1002", "FCS-ENG-1002: 未注册的还款方式生成器: %s"),

    /**
     * Rule-set presence check of {@link CalculationFacade#calculate} (orchestration step 2): a
     * RULE_DRIVEN calculation cannot run without a compiled rule set, and no built-in fallback
     * exists to degrade to.
     */
    RULE_DRIVEN_REQUIRES_RULE_SET("FCS-ENG-1003",
            "FCS-ENG-1003: RULE_DRIVEN 还款方式必须提供编译规则集"),

    /**
     * External-directory read check of {@link CalculationFacade#defaultsWithScripts} (orchestration
     * step 0): the directory must be listable. A script that fails to parse keeps its own
     * {@code FCS-RULE-*} loader code — that failure is already fail-fast.
     */
    FUNCTION_SCRIPT_DIR_LOAD_FAILED("FCS-ENG-1004", "FCS-ENG-1004: 外部函数脚本目录加载失败: %s"),

    /**
     * Multi-root ambiguity marker (ledger ruling R30 posture) of
     * {@link CalculationFacade#calculate} step 6: the event-table flow set changes sign more than
     * once, so several rates zero its NPV and the single root the solver returned is ambiguous.
     * v1 keeps {@link com.fincalc.domain.support.IrrCalculator#hasSingleSignChange} as the signal
     * and reports the ambiguity instead of pretending the tie-break「取最接近名义利率的最小正根」
     * was applied.
     */
    IRR_ROOT_AMBIGUOUS("FCS-ENG-1005", "FCS-ENG-1005: 现金流存在多个符号变化, IRR 结果存在多根歧义"),

    /**
     * Partial-rule-set fallback marker of {@link CalculationFacade#calculate} step 6: a period no
     * rule covered ran on the built-in equal-principal behaviour
     * ({@link com.fincalc.rule.generator.RuleDrivenGenerator#FALLBACK_MEMO}) and is surfaced as a
     * WARN issue so the schedule carries its own provenance.
     */
    RULE_FALLBACK_PERIOD("FCS-ENG-1006", "FCS-ENG-1006: 第 %s 期未被规则集覆盖, 已回落内置等额本金");

    private final String code;
    private final String message;

    EngineErrorCode(String code, String message) {
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
