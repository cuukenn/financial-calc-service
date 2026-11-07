package io.github.cuukenn.fincalc.rule.expression;

import io.github.cuukenn.fincalc.common.error.ErrorCode;
import java.util.Objects;

/**
 * The Excel error mapping of design §8.5 as data: one {@code ERROR} {@link Value} carries one of
 * these constants, mapping the spreadsheet error the calculation hit to its stable code.
 *
 * <p>Instances implement {@link ErrorCode} so a consumer that cannot continue with an error value
 * — for example {@link Expression#test(EvalContext)} — can raise a
 * {@link io.github.cuukenn.fincalc.common.error.BusinessException} carrying exactly the design-assigned code
 * {@code FCS-RULE-3005..3009} without a second layer of exception codes. Evaluation itself never
 * throws for these: errors flow as values so {@code IFERROR} (function library task) can catch
 * them.
 */
public final class FormulaError implements ErrorCode {

    /** {@code #DIV/0!} — a division whose divisor is zero. */
    public static final FormulaError DIV_ZERO =
            new FormulaError("FCS-RULE-3005", "FCS-RULE-3005: 除数为零 (#DIV/0!)");

    /** {@code #VALUE!} — an operand cannot be coerced to the required type. */
    public static final FormulaError VALUE_ERROR =
            new FormulaError("FCS-RULE-3006", "FCS-RULE-3006: 值类型不正确 (#VALUE!)");

    /** {@code #NUM!} — a numeric result is invalid, for example a fractional power of a negative base. */
    public static final FormulaError NUM_ERROR =
            new FormulaError("FCS-RULE-3007", "FCS-RULE-3007: 数值非法 (#NUM!)");

    /** {@code #N/A} — a value the computation needs is not available. */
    public static final FormulaError NA_ERROR =
            new FormulaError("FCS-RULE-3008", "FCS-RULE-3008: 值不可用 (#N/A)");

    /** {@code #NAME?} — a name evaluation could not resolve. */
    public static final FormulaError NAME_ERROR =
            new FormulaError("FCS-RULE-3009", "FCS-RULE-3009: 未知名称 (#NAME?)");

    private final String code;
    private final String message;

    private FormulaError(String code, String message) {
        this.code = Objects.requireNonNull(code, "code");
        this.message = Objects.requireNonNull(message, "message");
    }

    /** The stable error code, {@code FCS-RULE-3005..3009} per design §8.5. */
    @Override
    public String code() {
        return code;
    }

    /**
     * Placeholder-free message naming the code and the Excel error it maps, usable directly as a
     * {@link io.github.cuukenn.fincalc.common.error.BusinessException} template.
     */
    @Override
    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return code;
    }
}
