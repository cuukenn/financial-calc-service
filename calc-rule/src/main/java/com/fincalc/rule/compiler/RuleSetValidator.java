package com.fincalc.rule.compiler;

import com.fincalc.domain.model.IssueLevel;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PrincipalPlanKind;
import com.fincalc.domain.model.SpecialActionKind;
import com.fincalc.domain.model.ValidationIssue;
import com.fincalc.rule.RuleErrorCode;
import com.fincalc.rule.model.RuleRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rule set's structural validation (Task 26): V1–V8, collected aggregate-then-throw — the
 * validator returns <em>every</em> finding and {@link RuleCompiler} throws
 * {@link RuleValidationException} once, so a front end can show all problems in one round trip.
 *
 * <p>Check-to-code mapping (brief's validation table):
 *
 * <ul>
 *   <li><strong>V1</strong> — {@code fromPeriod >= 1}, {@code fromPeriod <= toPeriod},
 *       {@code toPeriod <= element.periods()} → {@code FCS-RULE-2001}. The first two are already
 *       enforced by the {@link RuleRow} constructor at build time, so rows reaching this
 *       validator can only violate the bound against the element; the check is kept complete
 *       anyway.</li>
 *   <li><strong>V2</strong> — same-{@code priority} rows must not overlap →
 *       {@code FCS-RULE-2002}; the same code (distinct message) reports duplicate
 *       {@code ruleId}s, the Task 23 carry-forward: canonical {@code (priority, ruleId)}
     *       ordering must stay unambiguous.</li>
 *   <li><strong>V3</strong> — unless {@code allowPartial}, periods {@code 1..element.periods()}
 *       must be fully covered → {@code FCS-RULE-2001}. The check is range-based: a conditioned
 *       row counts as covering its range at compile time (its condition may hold at runtime); a
 *       gap under {@code allowPartial} is legal and resolves to {@code matched() == false}.</li>
 *   <li><strong>V4</strong> — the {@code PERCENT} rows' {@code principalValue} must sum to 100 →
 *       {@code FCS-RULE-2003}.</li>
 *   <li><strong>V5</strong> — when no algorithm-driven row ({@code EQUAL}/{@code INHERIT}) is
 *       present, the deterministic total — FIXED value × covered periods, plus CUSTOM sequence
 *       sums, plus the contract principal's PERCENT share — must equal
 *       {@code element.principal()} → {@code FCS-RULE-2003}. Algorithm-driven rows leave the
 *       total undetermined at compile time, so the check stays silent there.</li>
 *   <li><strong>V6</strong> — a {@code CUSTOM} row's {@code principalSequence} must hold exactly
 *       one comma-separated entry per covered period → {@code FCS-RULE-2003}.</li>
 *   <li><strong>V7</strong> — a row's {@code annualRate} is a fraction and must lie in
 *       {@code [0, 1]} → {@code FCS-RULE-2006}.</li>
 *   <li><strong>V8</strong> — {@code specialAction} must be one of the v1-supported
 *       {@code NONE}/{@code FEE_AMORTIZE}/{@code DEPOSIT_OFFSET}; the reserved
 *       {@code PREPAY}/{@code EXTEND} are rejected → {@code FCS-RULE-2005}.</li>
 * </ul>
 *
 * <p>The validator is stateless; every finding is {@link com.fincalc.domain.model.IssueLevel#ERROR}.
 */
public final class RuleSetValidator {

    /** The v1-supported one-off actions (V8); the enum's remaining values are reserved for v2. */
    private static final Set<SpecialActionKind> SUPPORTED_ACTIONS =
            EnumSet.of(SpecialActionKind.NONE, SpecialActionKind.FEE_AMORTIZE,
                    SpecialActionKind.DEPOSIT_OFFSET);

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Validates the rows against the element, collecting all findings. */
    public List<ValidationIssue> validate(List<RuleRow> rows, LeaseElement element,
            boolean allowPartial) {
        List<ValidationIssue> issues = new ArrayList<>();
        checkRowRanges(rows, element, issues);
        checkDuplicatesAndOverlaps(rows, issues);
        checkCoverage(rows, element, allowPartial, issues);
        checkPrincipalPlans(rows, element, issues);
        checkAnnualRates(rows, issues);
        checkSpecialActions(rows, issues);
        return issues;
    }

    /** V1: every row's range stays inside {@code 1..element.periods()}. */
    private static void checkRowRanges(List<RuleRow> rows, LeaseElement element,
            List<ValidationIssue> issues) {
        for (RuleRow row : rows) {
            if (row.fromPeriod() < 1 || row.fromPeriod() > row.toPeriod()
                    || row.toPeriod() > element.periods()) {
                issues.add(issue(RuleErrorCode.INVALID_ROW_RANGE, String.format(
                        "规则 %s 覆盖 %d..%d，超出元素期间范围 [1, %d]",
                        row.ruleId(), row.fromPeriod(), row.toPeriod(), element.periods())));
            }
        }
    }

    /** V2 plus the duplicate-ruleId carry-forward. */
    private static void checkDuplicatesAndOverlaps(List<RuleRow> rows,
            List<ValidationIssue> issues) {
        Set<String> seenIds = new HashSet<>();
        for (RuleRow row : rows) {
            if (!seenIds.add(row.ruleId())) {
                issues.add(issue(RuleErrorCode.PRIORITY_INTERVAL_OVERLAP,
                        "规则ID重复: " + row.ruleId()));
            }
        }
        TreeMap<Integer, List<RuleRow>> byPriority = new TreeMap<>();
        for (RuleRow row : rows) {
            byPriority.computeIfAbsent(row.priority(), p -> new ArrayList<>()).add(row);
        }
        for (List<RuleRow> group : byPriority.values()) {
            List<RuleRow> sorted = new ArrayList<>(group);
            sorted.sort(Comparator.comparingInt(RuleRow::fromPeriod)
                    .thenComparingInt(RuleRow::toPeriod));
            for (int i = 1; i < sorted.size(); i++) {
                RuleRow previous = sorted.get(i - 1);
                RuleRow current = sorted.get(i);
                if (current.fromPeriod() <= previous.toPeriod()) {
                    issues.add(issue(RuleErrorCode.PRIORITY_INTERVAL_OVERLAP, String.format(
                            "优先级 %d 上规则 %s(%d..%d) 与规则 %s(%d..%d) 期间重叠",
                            previous.priority(), previous.ruleId(),
                            previous.fromPeriod(), previous.toPeriod(),
                            current.ruleId(), current.fromPeriod(), current.toPeriod())));
                }
            }
        }
    }

    /** V3: full coverage of {@code 1..element.periods()} unless {@code allowPartial}. */
    private static void checkCoverage(List<RuleRow> rows, LeaseElement element,
            boolean allowPartial, List<ValidationIssue> issues) {
        if (allowPartial) {
            return;
        }
        int periods = element.periods();
        boolean[] covered = new boolean[periods + 1];
        for (RuleRow row : rows) {
            int from = Math.max(1, row.fromPeriod());
            int to = Math.min(periods, row.toPeriod());
            for (int p = from; p <= to; p++) {
                covered[p] = true;
            }
        }
        List<String> gaps = new ArrayList<>();
        int start = -1;
        for (int p = 1; p <= periods; p++) {
            if (!covered[p] && start < 0) {
                start = p;
            } else if (covered[p] && start > 0) {
                gaps.add(start + ".." + (p - 1));
                start = -1;
            }
        }
        if (start > 0) {
            gaps.add(start + ".." + periods);
        }
        if (!gaps.isEmpty()) {
            issues.add(issue(RuleErrorCode.INVALID_ROW_RANGE,
                    "期间 1.." + periods + " 未被规则完整覆盖，缺口: " + String.join(", ", gaps)
                            + "（如需允许缺口请开启 allowPartial）"));
        }
    }

    /** V4/V5/V6 in one pass: percent totals, deterministic plan total, CUSTOM sequences. */
    private static void checkPrincipalPlans(List<RuleRow> rows, LeaseElement element,
            List<ValidationIssue> issues) {
        BigDecimal percentSum = BigDecimal.ZERO;
        boolean hasPercentRow = false;
        boolean hasAlgorithmDrivenRow = false;
        boolean anySequenceInvalid = false;
        BigDecimal deterministicTotal = BigDecimal.ZERO;

        for (RuleRow row : rows) {
            int coveredPeriods = row.toPeriod() - row.fromPeriod() + 1;
            switch (row.principalPlan()) {
                case PERCENT:
                    hasPercentRow = true;
                    percentSum = percentSum.add(orZero(row.principalValue()));
                    break;
                case FIXED:
                    deterministicTotal = deterministicTotal
                            .add(orZero(row.principalValue())
                                    .multiply(BigDecimal.valueOf(coveredPeriods)));
                    break;
                case CUSTOM:
                    BigDecimal sequenceSum = parseSequence(row, issues);
                    if (sequenceSum == null) {
                        anySequenceInvalid = true;
                    } else {
                        deterministicTotal = deterministicTotal.add(sequenceSum);
                    }
                    break;
                case EQUAL:
                case INHERIT:
                    hasAlgorithmDrivenRow = true;
                    break;
                case NONE:
                default:
                    break;
            }
        }

        if (hasPercentRow && percentSum.compareTo(HUNDRED) != 0) {
            issues.add(issue(RuleErrorCode.PRINCIPAL_PLAN_TOTAL,
                    "PERCENT 行 principalValue 合计应为 100，实际 " + percentSum.toPlainString()));
        }
        if (!rows.isEmpty() && !hasAlgorithmDrivenRow && !anySequenceInvalid) {
            deterministicTotal = deterministicTotal.add(element.principal()
                    .multiply(percentSum).divide(HUNDRED));
            if (deterministicTotal.compareTo(element.principal()) != 0) {
                issues.add(issue(RuleErrorCode.PRINCIPAL_PLAN_TOTAL, String.format(
                        "本金计划合计 %s 与本金 %s 不一致（FIXED 值×期数 + CUSTOM 序列合计 + PERCENT 份额）",
                        deterministicTotal.toPlainString(), element.principal().toPlainString())));
            }
        }
    }

    /**
     * V6: parses one CUSTOM row's comma-separated sequence, reporting a length/blank/number
     * finding per row. Returns the sequence's sum for V5, or {@code null} when the sequence is
     * invalid (V5 then skips the total check — the sequence problem is already reported).
     */
    private static BigDecimal parseSequence(RuleRow row, List<ValidationIssue> issues) {
        String sequence = row.principalSequence();
        if (sequence == null || sequence.trim().isEmpty()) {
            issues.add(issue(RuleErrorCode.PRINCIPAL_PLAN_TOTAL,
                    "规则 " + row.ruleId() + " 为 CUSTOM 计划但缺少 principalSequence"));
            return null;
        }
        String[] entries = sequence.split(",");
        int coveredPeriods = row.toPeriod() - row.fromPeriod() + 1;
        if (entries.length != coveredPeriods) {
            issues.add(issue(RuleErrorCode.PRINCIPAL_PLAN_TOTAL, String.format(
                    "规则 %s 的 principalSequence 含 %d 项，与覆盖期数 %d 不一致",
                    row.ruleId(), entries.length, coveredPeriods)));
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (String entry : entries) {
            try {
                sum = sum.add(new BigDecimal(entry.trim()));
            } catch (NumberFormatException e) {
                issues.add(issue(RuleErrorCode.PRINCIPAL_PLAN_TOTAL,
                        "规则 " + row.ruleId() + " 的 principalSequence 含非数值项: " + entry.trim()));
                return null;
            }
        }
        return sum;
    }

    /** V7: a row's annual rate is a fraction in {@code [0, 1]}. */
    private static void checkAnnualRates(List<RuleRow> rows, List<ValidationIssue> issues) {
        for (RuleRow row : rows) {
            BigDecimal rate = row.annualRate();
            if (rate != null
                    && (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0)) {
                issues.add(issue(RuleErrorCode.ANNUAL_RATE_OUT_OF_RANGE,
                        "规则 " + row.ruleId() + " 年利率 " + rate.toPlainString()));
            }
        }
    }

    /** V8: only the v1-supported one-off actions compile. */
    private static void checkSpecialActions(List<RuleRow> rows, List<ValidationIssue> issues) {
        for (RuleRow row : rows) {
            if (!SUPPORTED_ACTIONS.contains(row.specialAction())) {
                issues.add(issue(RuleErrorCode.UNSUPPORTED_SPECIAL_ACTION, String.format(
                        "规则 %s 的动作 %s 尚未支持（v1 仅支持 NONE、FEE_AMORTIZE、DEPOSIT_OFFSET）",
                        row.ruleId(), row.specialAction())));
            }
        }
    }

    private static ValidationIssue issue(RuleErrorCode code, String detail) {
        return new ValidationIssue(IssueLevel.ERROR, code.code(),
                String.format(code.message(), detail), null);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
