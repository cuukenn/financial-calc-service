package com.fincalc.domain.model;

import java.util.Objects;

/**
 * One validation finding of a calculation: a stable {@code FCS-CALC-*} code, a human-readable
 * message and, where applicable, the period the finding belongs to.
 */
public final class ValidationIssue {

    private final IssueLevel level;
    private final String code;
    private final String message;
    private final Integer periodNo;

    public ValidationIssue(IssueLevel level, String code, String message, Integer periodNo) {
        this.level = Objects.requireNonNull(level, "level");
        this.code = Objects.requireNonNull(code, "code");
        this.message = Objects.requireNonNull(message, "message");
        this.periodNo = periodNo;
    }

    public IssueLevel level() {
        return level;
    }

    /** Stable error code, e.g. {@code FCS-CALC-3001}. */
    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** Period the finding belongs to, or {@code null} when it concerns the whole schedule. */
    public Integer periodNo() {
        return periodNo;
    }

    @Override
    public String toString() {
        return periodNo == null
                ? level + " " + code + ": " + message
                : level + " " + code + " (期次 " + periodNo + "): " + message;
    }
}
