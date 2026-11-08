package io.github.cuukenn.fincalc.domain.metric;

/**
 * The side of the transaction whose cash flows the §9.5 metrics read (R103).
 *
 * <p>{@link #LESSOR} is the existing v1 posture: the outflow is the funded principal at t0 and the
 * inflows are the rents, fees, deposit and residual — which is what
 * {@link io.github.cuukenn.fincalc.domain.support.cashflow.StandardCashFlowBuilder} emits and what
 * every current test, golden table and metric asserts. It is the default, so a run that never names
 * a perspective is the run v1 produced.
 *
 * <p>{@link #LESSEE} reads the same schedule from the paying side: the flow signs flip, so the
 * principal funding is an inflow and the rents are outflows. Per R109 it drives only the BORROWER_*
 * metric family; no generator, validator or cash-flow behaviour changes.
 */
public enum MetricPerspective {
    LESSOR,
    LESSEE
}
