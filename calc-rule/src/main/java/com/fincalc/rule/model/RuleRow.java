package com.fincalc.rule.model;

import com.fincalc.common.error.BusinessException;
import com.fincalc.domain.model.InterestTreatmentKind;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.PrincipalPlanKind;
import com.fincalc.domain.model.SpecialActionKind;
import com.fincalc.rule.RuleErrorCode;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One immutable rule row, mirroring the rule sheet's columns (design §8.2).
 *
 * <p>House rule R56: the row reuses the calc-domain enums
 * {@link PrincipalPlanKind}, {@link InterestTreatmentKind}, {@link SpecialActionKind} and
 * {@link PaymentTiming} instead of redefining them — they already carry the reserved
 * {@code PREPAY}/{@code EXTEND} actions of design §8.2.2 — and adds the §8.2 columns the
 * sheet carries beyond the brief's list:
 *
 * <ul>
 *   <li>{@code stage} — readability only; never enters the rule-set hash;</li>
 *   <li>{@code condition} — the row's condition expression, kept <em>unparsed</em> here;
 *       Task 26 owns expression compilation and parses it into a
 *       {@code Predicate<EvalContext>} attached via {@link CompiledRule#withCondition}.</li>
 * </ul>
 *
 * <p>{@code principalValue} (a number) and {@code principalSequence} (the raw text) stay
 * separate: when Task 26 parses §8.2 column J's {@code CUSTOM} comma-list it fills
 * {@code principalSequence}, leaving {@code principalValue} untouched.
 *
 * <p>Nullable fields mean "the row leaves it open": {@code annualRate}, {@code rateBase},
 * {@code principalValue}, {@code principalSequence}, {@code actionParam}, {@code condition},
 * {@code memo} and {@code cellRef}. {@code memo}, {@code rowNumber} and {@code cellRef} are
 * presentation/provenance columns — they never enter the rule-set hash (R55).
 */
public final class RuleRow {

    private final String ruleId;
    private final int priority;
    private final int fromPeriod;
    private final int toPeriod;
    private final BigDecimal annualRate;
    private final RateBase rateBase;
    private final PrincipalPlanKind principalPlan;
    private final BigDecimal principalValue;
    private final String principalSequence;
    private final InterestTreatmentKind interestTreatment;
    private final PaymentTiming paymentTiming;
    private final SpecialActionKind specialAction;
    private final String actionParam;
    private final int stage;
    private final String condition;
    private final String memo;
    private final int rowNumber;
    private final String cellRef;

    /**
     * Creates a rule row, validating the period range it covers.
     *
     * <p>House rule R57: the only business check raised here is the row range, with
     * {@link RuleErrorCode#INVALID_ROW_RANGE} — a row must cover {@code fromPeriod >= 1} and
     * {@code fromPeriod <= toPeriod}. All other column semantics (e.g. a {@code FIXED} plan
     * carrying a value) are compiled by later tasks at their own raise sites.
     *
     * @param ruleId             the row's identifier; never {@code null}
     * @param priority           resolution priority; lower wins (R53)
     * @param fromPeriod         first covered period, one-based; must be {@code >= 1}
     * @param toPeriod           last covered period; must be {@code >= fromPeriod}
     * @param annualRate         the row's annual rate, or {@code null} to leave the rate open
     *                           and fall back to the caller's default
     * @param rateBase           the base the rate accrues against, or {@code null} to leave
     *                           the base open (R54)
     * @param principalPlan      how principal is repaid; never {@code null}
     * @param principalValue     the FIXED/PERCENT principal amount, or {@code null}
     * @param principalSequence  the raw CUSTOM principal sequence text (unparsed until
     *                           Task 26), or {@code null}
     * @param interestTreatment  what happens to the period's interest; never {@code null}
     * @param paymentTiming      whether rent is paid in advance or in arrears; never {@code null}
     * @param specialAction      the one-off action attached to the row; never {@code null}
     * @param actionParam        the action's parameter, or {@code null}
     * @param stage              the sheet's stage column; readability only (R56)
     * @param condition          the raw condition expression, unparsed until Task 26 (R56),
     *                           or {@code null} for an unconditional row
     * @param memo               the row's memo; presentation only, excluded from the hash (R55)
     * @param rowNumber          the sheet row the rule came from; provenance only
     * @param cellRef            the sheet cell the rule came from; provenance only
     * @throws BusinessException with {@link RuleErrorCode#INVALID_ROW_RANGE} when the period
     *                           range is invalid
     */
    public RuleRow(String ruleId, int priority, int fromPeriod, int toPeriod,
            BigDecimal annualRate, RateBase rateBase, PrincipalPlanKind principalPlan,
            BigDecimal principalValue, String principalSequence,
            InterestTreatmentKind interestTreatment, PaymentTiming paymentTiming,
            SpecialActionKind specialAction, String actionParam, int stage, String condition,
            String memo, int rowNumber, String cellRef) {
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.principalPlan = Objects.requireNonNull(principalPlan, "principalPlan");
        this.interestTreatment = Objects.requireNonNull(interestTreatment, "interestTreatment");
        this.paymentTiming = Objects.requireNonNull(paymentTiming, "paymentTiming");
        this.specialAction = Objects.requireNonNull(specialAction, "specialAction");
        if (fromPeriod < 1 || fromPeriod > toPeriod) {
            throw new BusinessException(RuleErrorCode.INVALID_ROW_RANGE,
                    ruleId + ": fromPeriod=" + fromPeriod + ", toPeriod=" + toPeriod);
        }
        this.priority = priority;
        this.fromPeriod = fromPeriod;
        this.toPeriod = toPeriod;
        this.annualRate = annualRate;
        this.rateBase = rateBase;
        this.principalValue = principalValue;
        this.principalSequence = principalSequence;
        this.actionParam = actionParam;
        this.stage = stage;
        this.condition = condition;
        this.memo = memo;
        this.rowNumber = rowNumber;
        this.cellRef = cellRef;
    }

    /** The row's identifier; never {@code null}. */
    public String ruleId() {
        return ruleId;
    }

    /** Resolution priority; lower wins (R53). */
    public int priority() {
        return priority;
    }

    /** First covered period, one-based. */
    public int fromPeriod() {
        return fromPeriod;
    }

    /** Last covered period, inclusive. */
    public int toPeriod() {
        return toPeriod;
    }

    /** The row's annual rate, or {@code null} when the row leaves the rate open. */
    public BigDecimal annualRate() {
        return annualRate;
    }

    /** The base the rate accrues against, or {@code null} when the row leaves the base open (R54). */
    public RateBase rateBase() {
        return rateBase;
    }

    /** How principal is repaid over the covered periods; never {@code null}. */
    public PrincipalPlanKind principalPlan() {
        return principalPlan;
    }

    /** The FIXED/PERCENT principal amount, or {@code null}. */
    public BigDecimal principalValue() {
        return principalValue;
    }

    /** The raw CUSTOM principal sequence text, or {@code null}; unparsed until Task 26. */
    public String principalSequence() {
        return principalSequence;
    }

    /** What happens to the period's interest; never {@code null}. */
    public InterestTreatmentKind interestTreatment() {
        return interestTreatment;
    }

    /** Whether rent is paid in advance or in arrears; never {@code null}. */
    public PaymentTiming paymentTiming() {
        return paymentTiming;
    }

    /** The one-off action attached to the row; never {@code null}. */
    public SpecialActionKind specialAction() {
        return specialAction;
    }

    /** The action's parameter, or {@code null}. */
    public String actionParam() {
        return actionParam;
    }

    /** The sheet's stage column; readability only, never enters the hash (R55/R56). */
    public int stage() {
        return stage;
    }

    /** The raw condition expression, or {@code null} for an unconditional row; unparsed until Task 26. */
    public String condition() {
        return condition;
    }

    /** The row's memo; presentation only (R55). */
    public String memo() {
        return memo;
    }

    /** The sheet row the rule came from; provenance only (R55). */
    public int rowNumber() {
        return rowNumber;
    }

    /** The sheet cell the rule came from; provenance only (R55). */
    public String cellRef() {
        return cellRef;
    }
}
