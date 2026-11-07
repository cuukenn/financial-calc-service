package io.github.cuukenn.fincalc.domain.model;

import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Summary of a {@link Schedule}. All totals and yield metrics live in {@link FinancialMetrics}
 * so new metrics never change this contract.
 */
public final class ScheduleHeader {

    private final FinancialMetrics metrics;
    private final int periods;
    private final LocalDate firstDate;
    private final LocalDate lastDate;
    private final String solver;
    private final BigDecimal roundingDifference;

    public ScheduleHeader(FinancialMetrics metrics, int periods, LocalDate firstDate,
            LocalDate lastDate, String solver, BigDecimal roundingDifference) {
        this.metrics = metrics == null ? FinancialMetrics.empty() : metrics;
        this.periods = periods;
        this.firstDate = firstDate;
        this.lastDate = lastDate;
        this.solver = solver;
        this.roundingDifference = roundingDifference;
    }

    /** Aggregated totals and yield metrics; codes with {@code null} values are not computable. */
    public FinancialMetrics metrics() {
        return metrics;
    }

    public int periods() {
        return periods;
    }

    public LocalDate firstDate() {
        return firstDate;
    }

    public LocalDate lastDate() {
        return lastDate;
    }

    /** How the periodic payment was obtained, e.g. {@code CLOSED_FORM} or {@code ITERATIVE}. */
    public String solver() {
        return solver;
    }

    /**
     * Rounding difference absorbed by the tail adjustment, signed (P-09: 不得取绝对值后丢弃符号).
     * Positive means the tail adjustment charged more in the final rent, negative means it
     * charged less.
     */
    public BigDecimal roundingDifference() {
        return roundingDifference;
    }
}
