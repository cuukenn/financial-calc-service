package com.fincalc.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One installment (period) of an amortization schedule. All amounts are {@link BigDecimal};
 * {@code rentDue} is the total payable of the period (principal + interest + fee + tax).
 */
public final class Installment {

    private final int periodNo;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final int days;
    private final BigDecimal openingBalance;
    private final BigDecimal principalDue;
    private final BigDecimal interestAccrued;
    private final BigDecimal interestPaid;
    private final BigDecimal fee;
    private final BigDecimal tax;
    private final BigDecimal rentDue;
    private final BigDecimal closingBalance;
    private final BigDecimal cumulativePrincipal;
    private final BigDecimal cumulativeInterest;
    private final BigDecimal appliedRate;
    private final String memo;

    public Installment(int periodNo, LocalDate startDate, LocalDate endDate, int days,
            BigDecimal openingBalance, BigDecimal principalDue, BigDecimal interestAccrued,
            BigDecimal interestPaid, BigDecimal fee, BigDecimal tax, BigDecimal rentDue,
            BigDecimal closingBalance, BigDecimal cumulativePrincipal, BigDecimal cumulativeInterest,
            BigDecimal appliedRate, String memo) {
        this.periodNo = periodNo;
        this.startDate = startDate;
        this.endDate = endDate;
        this.days = days;
        this.openingBalance = openingBalance;
        this.principalDue = principalDue;
        this.interestAccrued = interestAccrued;
        this.interestPaid = interestPaid;
        this.fee = fee;
        this.tax = tax;
        this.rentDue = rentDue;
        this.closingBalance = closingBalance;
        this.cumulativePrincipal = cumulativePrincipal;
        this.cumulativeInterest = cumulativeInterest;
        this.appliedRate = appliedRate;
        this.memo = memo;
    }

    /** One-based period number, starting at 1. */
    public int periodNo() {
        return periodNo;
    }

    /** First day of the interest accrual window (inclusive). */
    public LocalDate startDate() {
        return startDate;
    }

    /** Last day of the interest accrual window (inclusive). */
    public LocalDate endDate() {
        return endDate;
    }

    /** Number of days in the accrual window, derived from the day count convention. */
    public int days() {
        return days;
    }

    /** Principal balance before this installment. */
    public BigDecimal openingBalance() {
        return openingBalance;
    }

    /** Principal repaid by this installment. */
    public BigDecimal principalDue() {
        return principalDue;
    }

    /** Interest accrued over the period. */
    public BigDecimal interestAccrued() {
        return interestAccrued;
    }

    /** Interest actually paid this period; differs from accrued under capitalization or deferral. */
    public BigDecimal interestPaid() {
        return interestPaid;
    }

    /** Fee allocated to this period. */
    public BigDecimal fee() {
        return fee;
    }

    /** VAT portion of this period's amounts; {@code null} means no VAT split. */
    public BigDecimal tax() {
        return tax;
    }

    /** Total payable of the period: principal + interest + fee + tax. */
    public BigDecimal rentDue() {
        return rentDue;
    }

    /** Principal balance after this installment. */
    public BigDecimal closingBalance() {
        return closingBalance;
    }

    /** Running total of principal repaid through this period. */
    public BigDecimal cumulativePrincipal() {
        return cumulativePrincipal;
    }

    /** Running total of interest paid through this period. */
    public BigDecimal cumulativeInterest() {
        return cumulativeInterest;
    }

    /** Annual rate actually applied to this period, as a fraction. */
    public BigDecimal appliedRate() {
        return appliedRate;
    }

    /** Free-form provenance note, e.g. the rule row {@code R003} that produced this period. */
    public String memo() {
        return memo;
    }
}
