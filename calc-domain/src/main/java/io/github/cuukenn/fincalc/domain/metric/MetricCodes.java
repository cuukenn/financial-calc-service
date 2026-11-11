package io.github.cuukenn.fincalc.domain.metric;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stable codes of the financial metrics defined by design document §9.5.
 *
 * <p>The codes are part of the output contract (design document §7.2: output fields only grow),
 * so they are constants here rather than strings scattered across the metric implementations.
 * {@link #ALL} is the immutable set of every built-in code and is what tests and documentation
 * generators check against {@link MetricRegistry#defaults()}.
 */
public final class MetricCodes {

    /** Σ principalDue, scale 2. */
    public static final String TOTAL_PRINCIPAL = "TOTAL_PRINCIPAL";

    /** Σ interestPaid, scale 2. */
    public static final String TOTAL_INTEREST = "TOTAL_INTEREST";

    /** Σ fee, scale 2. */
    public static final String TOTAL_FEE = "TOTAL_FEE";

    /** Σ tax, scale 2. */
    public static final String TOTAL_TAX = "TOTAL_TAX";

    /** Σ rentDue, scale 2. */
    public static final String TOTAL_RENT = "TOTAL_RENT";

    /** Residual the schedule leaves outstanding at maturity, scale 2. */
    public static final String RESIDUAL_VALUE = "RESIDUAL_VALUE";

    /** TOTAL_RENT / principal, scale 6. */
    public static final String RENTAL_RATE = "RENTAL_RATE";

    /** Net present value at a periodic discount rate, scale 2. */
    public static final String NPV = "NPV";

    /** Net present value discounted by actual days over 365, scale 2. */
    public static final String XNPV = "XNPV";

    /** Periodic yield that zeroes the schedule's NPV, scale 10. */
    public static final String IRR_PERIOD = "IRR_PERIOD";

    /** Compound annualization of {@link #IRR_PERIOD}. */
    public static final String IRR_ANNUAL = "IRR_ANNUAL";

    /** Simple annualization of {@link #IRR_PERIOD} (Excel {@code IRR()*12}). */
    public static final String IRR_ANNUAL_SIMPLE = "IRR_ANNUAL_SIMPLE";

    /** Annual yield that zeroes the day-discounted flows, scale 10. */
    public static final String XIRR = "XIRR";

    /** Modified IRR, already annual. */
    public static final String MIRR = "MIRR";

    /** Static payback in years, scale 10. */
    public static final String PAYBACK_PERIOD = "PAYBACK_PERIOD";

    /** Discounted payback in years, scale 10. */
    public static final String DISCOUNTED_PAYBACK = "DISCOUNTED_PAYBACK";

    /** Macaulay duration in years, scale 10. */
    public static final String DURATION_MACAULAY = "DURATION_MACAULAY";

    /** Modified duration in years, scale 10. */
    public static final String DURATION_MODIFIED = "DURATION_MODIFIED";

    /** Convexity in years squared, scale 10. */
    public static final String CONVEXITY = "CONVEXITY";

    /** CAS 21 lease implicit rate (annual, CALC scale); needs asset value. */
    public static final String LEASE_IMPLICIT_RATE = "LEASE_IMPLICIT_RATE";

    /** Day-weighted average outstanding principal, scale 2. */
    public static final String AVG_OUTSTANDING_BALANCE = "AVG_OUTSTANDING_BALANCE";

    /** True annual rate; v1 equals {@link #XIRR}. */
    public static final String TRUE_ANNUAL_RATE = "TRUE_ANNUAL_RATE";

    /**
     * Borrower-view repayment total, scale 2: Σ {@code rentDue} + the terminal receipts the lessee
     * pays on top of the rents (the option price when {@code ResidualHandling#SEPARATE}, else 0,
     * plus {@code LeaseElement#guaranteedResidual} at full value). The rent already contains
     * principal, interest, fee and tax, and a refundable deposit is not a repayment, so neither is
     * added. Null on a {@code LESSOR} run. A presentation aid, not a regulatory metric: the
     * guaranteed residual is added at the lessor-side full amount, where CAS 21 第十八条（五）
     * counts only the expected-payable part on the lessee's side (ADR-0001).
     */
    public static final String BORROWER_TOTAL_REPAYMENT = "BORROWER_TOTAL_REPAYMENT";

    /**
     * Compound annualization of {@link #BORROWER_IRR_PERIOD}, scale 10 like the IRR family. The
     * same root as {@link #IRR_ANNUAL}: it labels the borrower view, it does not reprice the deal.
     */
    public static final String BORROWER_IRR_ANNUAL = "BORROWER_IRR_ANNUAL";

    /**
     * Simple annualization of {@link #BORROWER_IRR_PERIOD} (Excel {@code IRR()*m} convention); the
     * same root as {@link #IRR_ANNUAL_SIMPLE}.
     */
    public static final String BORROWER_IRR_ANNUAL_SIMPLE = "BORROWER_IRR_ANNUAL_SIMPLE";

    /**
     * Periodic root of the borrower (lessee) flow set, scale 10; always equal to
     * {@link #IRR_PERIOD}, which it is published separately from so the two annualizations can read
     * a recorded root instead of solving again.
     */
    public static final String BORROWER_IRR_PERIOD = "BORROWER_IRR_PERIOD";

    /**
     * Every built-in code, immutable, in §9.5 table order.
     *
     * <p>The tail — the {@code BORROWER_*} four — is the R103 perspective extension rather than
     * §9.5 table order: the borrower family is appended whole, in its own dependency order (the
     * periodic root before the two annual views it feeds). A LESSOR run carries all four
     * present-but-null.
     */
    public static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList(
                    TOTAL_PRINCIPAL, TOTAL_INTEREST, TOTAL_FEE, TOTAL_TAX, TOTAL_RENT,
                    RESIDUAL_VALUE, RENTAL_RATE, NPV, XNPV, IRR_PERIOD, IRR_ANNUAL,
                    IRR_ANNUAL_SIMPLE, XIRR, MIRR, PAYBACK_PERIOD, DISCOUNTED_PAYBACK,
                    DURATION_MACAULAY, DURATION_MODIFIED, CONVEXITY, AVG_OUTSTANDING_BALANCE,
                    TRUE_ANNUAL_RATE, LEASE_IMPLICIT_RATE, BORROWER_IRR_PERIOD, BORROWER_IRR_ANNUAL,
                    BORROWER_IRR_ANNUAL_SIMPLE, BORROWER_TOTAL_REPAYMENT)));

    private MetricCodes() {
    }
}
