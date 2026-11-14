package io.github.cuukenn.fincalc.domain.model;

/**
 * Which business product one calculation belongs to (form-field-gaps issue 15, A1).
 *
 * <p>The product identity lives in the <em>request layer</em> only —
 * {@link CalculationOptions#product()} — never on {@link LeaseElement}: it changes no cash flow and
 * no balance, so it is forbidden as an element field: an input that moves nothing is a decoration. The engine's calculation core (generators,
 * cash-flow table, metrics, §9.7) stays product-blind (R100: the engine is identical for both
 * scenarios); the identity selects which <em>additional validators</em> run over the finished
 * schedule (A2's threshold disclosures).
 *
 * <p>The default is {@link #LEASE} — the v1 posture, so every request that does not carry the
 * field (all existing callers, byte for byte) keeps its exact numbers and issue list.
 */
public enum ProductProfile {

    /** A lease calculation — the v1 posture and the default; no additional validators. */
    LEASE,

    /** A mortgage calculation — enables the regulatory-threshold disclosures (A2). */
    MORTGAGE
}
