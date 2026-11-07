package com.fincalc.rule.model;

/**
 * The principal base a rule row's annual rate accrues against (design §8.2).
 *
 * <p>House rule R54: the enum lives in {@code calc-rule} — {@code calc-domain} must stay
 * rule-free — and crosses the module boundary as a plain {@code String} via
 * {@link com.fincalc.domain.model.RuleOutcome#rateBase()}, which carries the {@link #name()}.
 */
public enum RateBase {

    /** The principal balance at the start of the period. */
    OPENING_BALANCE,

    /** The contract's original principal. */
    ORIGINAL_PRINCIPAL,

    /** The contract principal not yet repaid. */
    REMAINING_PRINCIPAL,

    /** A fixed amount carried by the row itself. */
    FIXED_AMOUNT
}
