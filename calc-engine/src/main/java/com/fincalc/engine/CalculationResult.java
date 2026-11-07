package com.fincalc.engine;

import com.fincalc.domain.model.EffectiveInterestTable;
import com.fincalc.domain.model.Schedule;
import java.util.Objects;

/**
 * Output of one engine run: the enriched, validated schedule plus the §9.7 effective-interest
 * table when one could be built.
 *
 * <p>{@code effectiveInterest} is nullable by design: IRR non-convergence is an expected outcome
 * (§9.5's「IRR 不收敛」), and the facade answers with {@code null} plus a WARN
 * {@code ValidationIssue} on the schedule instead of failing the run.
 */
public final class CalculationResult {

    private final String runId;
    private final Schedule schedule;
    private final EffectiveInterestTable effectiveInterest;

    /**
     * @param runId             the run's correlation id, 32 lowercase hex characters; never
     *                          {@code null}
     * @param schedule          the enriched, validated schedule; never {@code null}
     * @param effectiveInterest the §9.7 table, or {@code null} when no consistent effective rate
     *                          exists for the schedule
     */
    public CalculationResult(String runId, Schedule schedule,
            EffectiveInterestTable effectiveInterest) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.effectiveInterest = effectiveInterest;
    }

    /** The run's correlation id: {@code UUID.randomUUID()} without dashes. */
    public String runId() {
        return runId;
    }

    /** The enriched, validated schedule; never {@code null}. */
    public Schedule schedule() {
        return schedule;
    }

    /** The §9.7 effective-interest table, or {@code null} when none could be built. */
    public EffectiveInterestTable effectiveInterest() {
        return effectiveInterest;
    }
}
