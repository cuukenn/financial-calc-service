package com.fincalc.common.error;

/**
 * Unexpected failure that callers cannot act on, such as a broken SPI configuration or an
 * infrastructure error.
 *
 * <p>Contrast with {@link BusinessException}: a system exception signals a defect or environment
 * problem, should be logged for operators and is normally surfaced as a generic internal error.
 */
public class SystemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a system exception.
     *
     * @param message description of the failure
     * @param cause   underlying failure; may be {@code null}
     */
    public SystemException(String message, Throwable cause) {
        super(message, cause);
    }
}
