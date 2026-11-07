package io.github.cuukenn.fincalc.engine;

import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.port.RuleSetView;
import java.util.Objects;

/**
 * Input of one engine run: the lease element, the optional compiled rule set and the run options.
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
 */
public final class CalculationRequest {

    private final LeaseElement element;
    private final RuleSetView ruleSet;
    private final CalculationOptions options;

    /**
     * @param element the lease element to calculate; never {@code null}
     * @param ruleSet the compiled rule set, required for {@code RULE_DRIVEN}; may be {@code null}
     *                otherwise
     * @param options run options; {@code null} means {@link CalculationOptions#defaults()}
     */
    public CalculationRequest(LeaseElement element, RuleSetView ruleSet,
            CalculationOptions options) {
        this.element = Objects.requireNonNull(element, "element");
        this.ruleSet = ruleSet;
        this.options = options == null ? CalculationOptions.defaults() : options;
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
}
