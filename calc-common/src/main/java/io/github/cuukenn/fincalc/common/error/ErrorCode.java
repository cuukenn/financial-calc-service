package io.github.cuukenn.fincalc.common.error;

/**
 * Stable error identifier exposed to callers.
 *
 * <p>Codes follow the project convention {@code FCS-<module>-<4 digits>}, for example
 * {@code FCS-RULE-1001}. The code is part of the public contract and must never change once
 * released; only new codes may be added.
 */
public interface ErrorCode {

    /** Unique, stable error code such as {@code FCS-COM-0001}. */
    String code();

    /**
     * Human-readable message template used with {@link String#format(String, Object...)}.
     *
     * <p>Placeholders are filled in by {@link BusinessException} at construction time.
     */
    String message();
}
