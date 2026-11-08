package io.github.cuukenn.fincalc.engine;

import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.port.RuleSetView;
import java.util.Objects;

/**
 * Input of one engine run: the lease element, the optional compiled rule set, the run options and
 * the metric perspective.
 *
 * <p>{@code options} reuses {@link io.github.cuukenn.fincalc.domain.model.CalculationOptions} — the engine does
 * not redefine run options. A {@code null} options argument is normalized to
 * {@link CalculationOptions#defaults()} so every step downstream reads a non-null contract.
 *
 * <p>{@code ruleSet} is required (non-null) exactly when the element's method is
 * {@code RULE_DRIVEN}; the facade fails the run with
 * {@link EngineErrorCode#RULE_DRIVEN_REQUIRES_RULE_SET} otherwise. For the built-in methods a
 * rule set may still be supplied — the built-in generators consult it for per-period rates and
 * fall back to the element's nominal rate where it leaves the rate open.
 *
 * <p>{@code perspective} is the {@link CashFlowPerspective} the §9.5 metric run reads (R103). A
 * {@code null} perspective is normalized to {@link CashFlowPerspective#LESSOR}, so the three-argument
 * constructor and every existing caller keep the v1 run exactly. A {@code LESSEE} run reads the
 * mirrored flow set, so the flow-driven §9.5 codes follow that view — {@code NPV}/{@code XNPV}
 * change sign, {@code PAYBACK_PERIOD}/{@code DISCOUNTED_PAYBACK} answer {@code null} because the
 * first flow is money in, and the IRR family keeps the same root digit for digit — while the
 * {@code BORROWER_*} family becomes computable. Nothing outside the metric layer reads the view:
 * the schedule, its §9.4 event table (which stays the lessor table) and the validators are
 * unchanged (R109/R124).
 */
public final class CalculationRequest {

    private final LeaseElement element;
    private final RuleSetView ruleSet;
    private final CalculationOptions options;
    private final CashFlowPerspective perspective;

    /**
     * A lessor-view request — the v1 shape; delegates with {@link CashFlowPerspective#LESSOR}.
     *
     * @param element the lease element to calculate; never {@code null}
     * @param ruleSet the compiled rule set, required for {@code RULE_DRIVEN}; may be {@code null}
     *                otherwise
     * @param options run options; {@code null} means {@link CalculationOptions#defaults()}
     */
    public CalculationRequest(LeaseElement element, RuleSetView ruleSet,
            CalculationOptions options) {
        this(element, ruleSet, options, CashFlowPerspective.LESSOR);
    }

    /**
     * @param element     the lease element to calculate; never {@code null}
     * @param ruleSet     the compiled rule set, required for {@code RULE_DRIVEN}; may be
     *                    {@code null} otherwise
     * @param options     run options; {@code null} means {@link CalculationOptions#defaults()}
     * @param perspective whose side of the transaction the §9.5 metric run reads; {@code null}
     *                    means {@link CashFlowPerspective#LESSOR}
     */
    public CalculationRequest(LeaseElement element, RuleSetView ruleSet, CalculationOptions options,
            CashFlowPerspective perspective) {
        this.element = Objects.requireNonNull(element, "element");
        this.ruleSet = ruleSet;
        this.options = options == null ? CalculationOptions.defaults() : options;
        this.perspective = perspective == null ? CashFlowPerspective.LESSOR : perspective;
    }

    /** The lease element to calculate; never {@code null}. */
    public LeaseElement element() {
        return element;
    }

    /** Compiled rule set, or {@code null} when the run is pure built-in. */
    public RuleSetView ruleSet() {
        return ruleSet;
    }

    /** Run options; never {@code null}. */
    public CalculationOptions options() {
        return options;
    }

    /**
     * Whose side of the transaction the metric run reads (R103); never {@code null} —
     * {@link CashFlowPerspective#LESSOR} unless the caller asked for the lessee view.
     */
    public CashFlowPerspective perspective() {
        return perspective;
    }
}
