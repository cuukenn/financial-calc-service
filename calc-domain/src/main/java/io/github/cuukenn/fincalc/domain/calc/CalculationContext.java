package io.github.cuukenn.fincalc.domain.calc;

import java.util.Objects;

import io.github.cuukenn.fincalc.common.time.ClockProvider;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.support.NumericConfig;
import io.github.cuukenn.fincalc.domain.port.DateRollConvention;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import io.github.cuukenn.fincalc.domain.port.RuleSetView;

/**
 * Everything a generator needs for one run: the lease element, the resolved conventions, the
 * optional rule set and the run options.
 */
public final class CalculationContext {

    private final LeaseElement element;
    private final RuleSetView ruleSet;
    private final DayCountConvention dayCount;
    private final DateRollConvention dateRoll;
    private final ClockProvider clock;
    private final CalculationOptions options;
    private final NumericConfig numericConfig;

    public CalculationContext(LeaseElement element, RuleSetView ruleSet,
            DayCountConvention dayCount, DateRollConvention dateRoll,
            ClockProvider clock, CalculationOptions options) {
        this(element, ruleSet, dayCount, dateRoll, clock, options, NumericConfig.defaults());
    }

    public CalculationContext(LeaseElement element, RuleSetView ruleSet,
            DayCountConvention dayCount, DateRollConvention dateRoll,
            ClockProvider clock, CalculationOptions options, NumericConfig numericConfig) {
        this.element = Objects.requireNonNull(element, "element");
        this.ruleSet = ruleSet;
        this.dayCount = Objects.requireNonNull(dayCount, "dayCount");
        this.dateRoll = Objects.requireNonNull(dateRoll, "dateRoll");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.options = options == null ? CalculationOptions.defaults() : options;
        this.numericConfig = numericConfig == null ? NumericConfig.defaults() : numericConfig;
    }

    public LeaseElement element() {
        return element;
    }

    /** Compiled rule set, or {@code null} for a pure built-in run. */
    public RuleSetView ruleSet() {
        return ruleSet;
    }

    public DayCountConvention dayCount() {
        return dayCount;
    }

    public DateRollConvention dateRoll() {
        return dateRoll;
    }

    public ClockProvider clock() {
        return clock;
    }

    public CalculationOptions options() {
        return options;
    }

    /** 数值求解配置（逼近次数/容差/求根区间）；非测算要素，不改任何口径。 */
    public NumericConfig numericConfig() {
        return numericConfig;
    }
}
