package com.fincalc.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One period of the §9.7 effective-interest amortization table (会计入账侧), immutable.
 *
 * <ul>
 *   <li>{@code openingNetInvestment} — §9.7's 「期初净投资 = 期初余额 − 未摊销费用」. In v1 the
 *       domain carries no prepaid / unamortized-fee state (fees arrive per period on
 *       {@link Installment#fee()}), so the term reduces to the opening balance itself; when
 *       初始直接费用 enters the model, that subtraction plugs in at
 *       {@code EffectiveInterestCalculator}'s row-1 seeding (design ledger ruling R46).</li>
 *   <li>{@code interestIncome} — {@code openingNetInvestment × EIR_period} at scale 2; the last
 *       row additionally absorbs the terminal balance adjustment (bounded by the calculator's
 *       closing tolerance).</li>
 *   <li>{@code cashReceived} — the period's {@link Installment#rentDue()}.</li>
 *   <li>{@code principalRecovery} — {@code cashReceived − interestIncome}.</li>
 *   <li>{@code closingNetInvestment} — {@code openingNetInvestment − principalRecovery}.</li>
 * </ul>
 *
 * <p>All amounts carry the output scale of 2. The per-period difference against the nominal
 * schedule (§9.7's 「逐期利息差额」) is not stored here: it is obtained by joining this row with
 * the {@link Installment} of the same {@code periodNo} and subtracting
 * {@link Installment#interestPaid()} (design ledger ruling R50).
 */
public final class EffectiveInterestRow {

    private final int periodNo;
    private final BigDecimal openingNetInvestment;
    private final BigDecimal interestIncome;
    private final BigDecimal cashReceived;
    private final BigDecimal principalRecovery;
    private final BigDecimal closingNetInvestment;

    public EffectiveInterestRow(int periodNo, BigDecimal openingNetInvestment,
            BigDecimal interestIncome, BigDecimal cashReceived, BigDecimal principalRecovery,
            BigDecimal closingNetInvestment) {
        this.periodNo = periodNo;
        this.openingNetInvestment = Objects.requireNonNull(openingNetInvestment,
                "openingNetInvestment");
        this.interestIncome = Objects.requireNonNull(interestIncome, "interestIncome");
        this.cashReceived = Objects.requireNonNull(cashReceived, "cashReceived");
        this.principalRecovery = Objects.requireNonNull(principalRecovery, "principalRecovery");
        this.closingNetInvestment = Objects.requireNonNull(closingNetInvestment,
                "closingNetInvestment");
    }

    /** One-based period number, matching the {@link Installment#periodNo()} it was derived from. */
    public int periodNo() {
        return periodNo;
    }

    /** Net investment at the start of the period; row 1 is seeded from the schedule's own opening balance. */
    public BigDecimal openingNetInvestment() {
        return openingNetInvestment;
    }

    /** {@code openingNetInvestment × EIR_period}, rounded to scale 2; last row absorbs the bounded terminal adjustment. */
    public BigDecimal interestIncome() {
        return interestIncome;
    }

    /** Cash actually received this period: the installment's {@code rentDue}. */
    public BigDecimal cashReceived() {
        return cashReceived;
    }

    /** {@code cashReceived − interestIncome}. */
    public BigDecimal principalRecovery() {
        return principalRecovery;
    }

    /** {@code openingNetInvestment − principalRecovery}; row N equals the schedule's terminal balance. */
    public BigDecimal closingNetInvestment() {
        return closingNetInvestment;
    }
}
