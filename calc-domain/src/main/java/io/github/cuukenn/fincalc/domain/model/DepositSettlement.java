package io.github.cuukenn.fincalc.domain.model;

/**
 * How the deposit leaves the deal at settlement (退还/抵扣规则）.
 *
 * <p>The calibers (recorded 2026-11-14, form-field-gaps ticket 03):
 *
 * <ul>
 *   <li>{@link #REFUND} — the deposit (plus its interest when the element carries a deposit
 *       interest rate) is paid back to the lessee at maturity as its own cash-flow rows; the
 *       schedule amortizes the full contract principal.</li>
 *   <li>{@link #OFFSET_PRINCIPAL} — the deposit is consumed at collection: the schedule amortizes
 *       {@code 合同本金 − 保证金} ({@link LeaseElement#measuredPrincipal()}), the event table keeps
 *       both legs explicit (gross principal out, deposit in), and no settlement row exists. A
 *       deposit interest rate is a caliber contradiction here — the deposit never occupies a
 *       period — and is rejected at {@link LeaseElement} validation.</li>
 *   <li>{@link #OFFSET_FINAL_RENT} — the deposit (plus interest when carried) offsets the final
 *       rent: cash-wise this is the deposit collected early and returned at maturity against the
 *       last rent, which is the same event pair as a refund dated at maturity, so the event table
 *       is identical to {@link #REFUND}; the schedule's rent column stays the amortization rent
 *       and the offset lives in the deposit rows, never netted into 租金（读数不进租金）。
 *       The presentation label is the caller's; the cash is the run's.</li>
 * </ul>
 */
public enum DepositSettlement {

    /** Returned to the lessee at maturity, as its own rows. */
    REFUND,

    /** Consumed at collection against the financed principal. */
    OFFSET_PRINCIPAL,

    /** Applied against the final rent at maturity. */
    OFFSET_FINAL_RENT
}
