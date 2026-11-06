package com.fincalc.domain.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The §9.7 effective-interest amortization table: one {@link EffectiveInterestRow} per period of
 * the {@link Schedule} it was built from, plus the rate and the reconciliation total.
 *
 * <p>Immutable: the row list is an unmodifiable defensive copy and the rows themselves are
 * immutable. The table never participates in the {@link Schedule} main table —
 * {@code CalculationFacade} returns it as an additional result (附加结果) so the main flow
 * stays decoupled (§9.7).
 */
public final class EffectiveInterestTable {

    private final List<EffectiveInterestRow> rows;
    private final BigDecimal eir;
    private final BigDecimal totalInterestDifference;

    public EffectiveInterestTable(List<EffectiveInterestRow> rows, BigDecimal eir,
            BigDecimal totalInterestDifference) {
        this.rows = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(rows, "rows")));
        this.eir = Objects.requireNonNull(eir, "eir");
        this.totalInterestDifference = Objects.requireNonNull(totalInterestDifference,
                "totalInterestDifference");
    }

    /** One row per schedule period, in period order; unmodifiable. */
    public List<EffectiveInterestRow> rows() {
        return rows;
    }

    /**
     * The periodic effective-interest rate the table was built with — the raw root from
     * {@code IrrCalculator.periodIrr} (or the published {@code IRR_PERIOD}), stored as given,
     * not re-rounded.
     */
    public BigDecimal eir() {
        return eir;
    }

    /**
     * Σ {@code interestIncome} − Σ {@code installment.interestPaid()} at scale 2: the whole-table
     * difference between the effective-interest method and the nominal method the schedule was
     * generated with. Per-period differences come from joining the rows with the installments
     * on {@code periodNo} (design ledger ruling R50).
     */
    public BigDecimal totalInterestDifference() {
        return totalInterestDifference;
    }
}
