package io.github.cuukenn.fincalc.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One cash flow event of a calculation. {@code amount} is always positive; whether the event
 * is money in or out is carried by {@link CashFlowDirection}, never by the sign.
 */
public final class CashFlow {

    private final int sequence;
    private final LocalDate date;
    private final BigDecimal amount;
    private final CashFlowType type;
    private final CashFlowDirection direction;

    public CashFlow(int sequence, LocalDate date, BigDecimal amount,
            CashFlowType type, CashFlowDirection direction) {
        this.sequence = sequence;
        this.date = Objects.requireNonNull(date, "date");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.type = Objects.requireNonNull(type, "type");
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    /** Zero-based position within the schedule's cash flow list; stable ordering matters for IRR. */
    public int sequence() {
        return sequence;
    }

    public LocalDate date() {
        return date;
    }

    /** Positive magnitude; sign lives in {@link #direction()}. */
    public BigDecimal amount() {
        return amount;
    }

    public CashFlowType type() {
        return type;
    }

    public CashFlowDirection direction() {
        return direction;
    }
}
