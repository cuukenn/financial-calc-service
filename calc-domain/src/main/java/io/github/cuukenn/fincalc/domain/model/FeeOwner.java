package io.github.cuukenn.fincalc.domain.model;

/**
 * Who bears a fee (AGENTS.md 第 12 条「费用承担方」；form-field-gaps ticket 04 第一步).
 *
 * <p>The owner decides the fee's cash-flow direction on the lessor event table — the two
 * directions move the lessor's IRR oppositely, which is exactly why the owner must be explicit
 * instead of assumed:
 *
 * <ul>
 *   <li>{@link #LESSEE} — the lessee bears the fee and the lessor collects it: an INFLOW row at
 *       t0 (the v1 posture, the default so existing payloads keep their numbers).</li>
 *   <li>{@link #LESSOR} — the lessor bears the fee and pays it to a third party: an OUTFLOW row
 *       at t0. The R103 LESSEE mirror flips it back, so the money conservation holds on both
 *       views; only the IRRs move, in opposite directions.</li>
 * </ul>
 */
public enum FeeOwner {

    /** The lessee bears the fee; the lessor collects it (v1 posture, the default). */
    LESSEE,

    /** The lessor bears the fee and pays it out; the event table records an outflow. */
    LESSOR
}
