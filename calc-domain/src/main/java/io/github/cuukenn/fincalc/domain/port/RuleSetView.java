package io.github.cuukenn.fincalc.domain.port;

import java.math.BigDecimal;

import io.github.cuukenn.fincalc.domain.model.RuleOutcome;

/**
 * Read-only view over a compiled rule set, resolved per period.
 *
 * <p>The interface lives in the domain so the built-in generators can consult rules without
 * depending on the rule module that implements it.
 */
public interface RuleSetView {

    /**
     * Resolves the rule that governs one period.
     *
     * @param periodNo           one-based period number
     * @param openingBalance     principal balance at the start of the period
     * @param days               accrual days of the period
     * @param defaultAnnualRate  rate to fall back to when the rule leaves it open
     * @return the matched rule, or {@link RuleOutcome#none()} when no rule covers the period
     */
    RuleOutcome resolve(int periodNo, BigDecimal openingBalance, int days, BigDecimal defaultAnnualRate);
}
