package io.github.cuukenn.fincalc.rule.compiler;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.error.CommonErrorCode;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Raised by {@link RuleCompiler#compile} when the rule set has at least one ERROR-level
 * {@link ValidationIssue} (Task 26, aggregate-then-throw discipline): the compiler collects
 * <em>every</em> finding first — V1–V8, duplicate ruleIds, condition parse failures — and throws
 * this exception exactly once, so a front end can show all problems in one round trip.
 *
 * <p>Disclosed choice of the exception's own {@code ErrorCode}: per-issue stable codes live in
 * {@link #issues()} ({@code FCS-RULE-2001..2006} and the expression codes
 * {@code FCS-RULE-3001/3004/3009}); the exception itself reuses the existing
 * {@link CommonErrorCode#INVALID_ARGUMENT} ("FCS-COM-0001: 参数 %s 非法: %s") with the rule set named as the
 * invalid argument and the issue summary as the reason. No new {@code RuleErrorCode} constant is
 * invented for the exception envelope, because R57 forbids codes without a distinct raising check
 * — every distinct check here already has its own code on its issue.
 */
public final class RuleValidationException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final List<ValidationIssue> issues;

    /**
     * Creates the exception over the collected issues.
     *
     * @param issues every finding of the compilation, ERROR level included; never {@code null},
     *               never empty (the compiler only throws when at least one issue exists)
     */
    public RuleValidationException(List<ValidationIssue> issues) {
        super(CommonErrorCode.INVALID_ARGUMENT, "规则集", summarize(issues));
        this.issues = List.copyOf(issues);
    }

    /** Every collected finding, in collection order; immutable. */
    public List<ValidationIssue> issues() {
        return issues;
    }

    private static String summarize(List<ValidationIssue> issues) {
        Objects.requireNonNull(issues, "issues");
        StringJoiner joiner = new StringJoiner("; ");
        for (ValidationIssue issue : issues) {
            joiner.add(issue.toString());
        }
        return joiner.toString();
    }
}
