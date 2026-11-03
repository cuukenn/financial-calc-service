package com.fincalc.domain.calc;

import java.util.Objects;

import com.fincalc.common.time.ClockProvider;
import com.fincalc.domain.model.CalculationOptions;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.port.DateRollConvention;
import com.fincalc.domain.port.DayCountConvention;
import com.fincalc.domain.port.RuleSetView;

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

    public CalculationContext(LeaseElement element, RuleSetView ruleSet,
            DayCountConvention dayCount, DateRollConvention dateRoll,
            ClockProvider clock, CalculationOptions options) {
        this.element = Objects.requireNonNull(element, "element");
        this.ruleSet = ruleSet;
        this.dayCount = Objects.requireNonNull(dayCount, "dayCount");
        this.dateRoll = Objects.requireNonNull(dateRoll, "dateRoll");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.options = options == null ? CalculationOptions.defaults() : options;
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
}
