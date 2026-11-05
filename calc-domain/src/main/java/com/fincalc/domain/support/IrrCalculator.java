package com.fincalc.domain.support;

import com.fincalc.domain.model.CashFlow;
import com.fincalc.domain.model.CashFlowDirection;
import com.fincalc.domain.port.RootSolver;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Yield metrics over a schedule's cash flows (design document §9.5).
 *
 * <p>Sign convention: {@code INFLOW} is positive, {@code OUTFLOW} is negative, so the flows sum
 * to what the perspective actually pays or receives. Period metrics discount by the flow's
 * {@code sequence}; X-variants discount by actual days over 365 from the earliest flow date.
 *
 * <p>Root finding goes through the {@link RootSolver} port so the numeric method is substitutable;
 * the bracket follows the design convention {@code [-0.9999, 10]}. A yield that cannot be found
 * (no sign change, no root in the bracket, budget exhausted) is {@code null}, and the caller
 * records the {@code FCS-CALC-3003} warning — a metric is never fabricated.
 */
public final class IrrCalculator {

    /** Search bracket for periodic and annual yields. */
    public static final BigDecimal RATE_LOWER = new BigDecimal("-0.9999");
    public static final BigDecimal RATE_UPPER = new BigDecimal("10");

    private static final MathContext MC = MathContext.DECIMAL128;
    /** Internal precision for the transcendental steps of a decimal power. */
    private static final MathContext CALC = new MathContext(40, RoundingMode.HALF_EVEN);
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal SERIES_STOP = new BigDecimal("1e-36");
    /** ln(2) straight from its own atanh series, used to range-reduce ln and exp. */
    private static final BigDecimal LN2 =
            atanhSeries(BigDecimal.ONE.divide(BigDecimal.valueOf(3), CALC)).multiply(TWO, CALC);
    private static final int RATE_SCALE = 12;
    private static final RootSolver DEFAULT_SOLVER = new BisectionNewtonSolver();

    private IrrCalculator() {
    }

    /** Net present value at a periodic rate, discounting each flow by its {@code sequence}. */
    public static BigDecimal npv(List<CashFlow> flows, BigDecimal periodRate) {
        requireFlows(flows);
        Objects.requireNonNull(periodRate, "periodRate");
        BigDecimal growth = BigDecimal.ONE.add(periodRate);
        BigDecimal total = BigDecimal.ZERO;
        for (CashFlow flow : flows) {
            BigDecimal factor = growth.pow(flow.sequence(), MC);
            total = total.add(signed(flow).divide(factor, MC), MC);
        }
        return total;
    }

    /** Net present value at an annual rate, discounting by actual days over 365. */
    public static BigDecimal xnpv(List<CashFlow> flows, BigDecimal annualRate) {
        requireFlows(flows);
        Objects.requireNonNull(annualRate, "annualRate");
        LocalDate origin = flows.get(0).date();
        for (CashFlow flow : flows) {
            if (flow.date().compareTo(origin) < 0) {
                origin = flow.date();
            }
        }
        BigDecimal growth = BigDecimal.ONE.add(annualRate);
        requirePositiveBase(growth, annualRate);
        BigDecimal total = BigDecimal.ZERO;
        for (CashFlow flow : flows) {
            BigDecimal years = BigDecimal.valueOf(ChronoUnit.DAYS.between(origin, flow.date()))
                    .divide(DAYS_PER_YEAR, MC);
            // BigDecimal has no decimal-exponent pow on JDK 11: split integer and fractional parts.
            BigDecimal factor = decimalPow(growth, years);
            total = total.add(signed(flow).divide(factor, MC), MC);
        }
        return total;
    }

    /** Periodic yield with NPV zero; {@code null} when it cannot be found. */
    public static BigDecimal periodIrr(List<CashFlow> flows) {
        return periodIrr(flows, DEFAULT_SOLVER);
    }

    public static BigDecimal periodIrr(List<CashFlow> flows, RootSolver solver) {
        requireFlows(flows);
        Objects.requireNonNull(solver, "solver");
        if (!hasSignChange(flows)) {
            return null;
        }
        return solver.solve(rate -> npv(flows, rate), RATE_LOWER, RATE_UPPER,
                BisectionNewtonSolver.DEFAULT_TOLERANCE);
    }

    /** Annualized XIRR: the annual rate at which the day-discounted flows sum to zero. */
    public static BigDecimal xirr(List<CashFlow> flows) {
        return xirr(flows, DEFAULT_SOLVER);
    }

    public static BigDecimal xirr(List<CashFlow> flows, RootSolver solver) {
        requireFlows(flows);
        Objects.requireNonNull(solver, "solver");
        if (!hasSignChange(flows)) {
            return null;
        }
        return solver.solve(rate -> xnpv(flows, rate), RATE_LOWER, RATE_UPPER,
                BisectionNewtonSolver.DEFAULT_TOLERANCE);
    }

    /** Compound annualization: {@code (1 + periodIrr) ^ periodsPerYear - 1}, at {@code 12} scales. */
    public static BigDecimal annualize(BigDecimal periodIrr, int periodsPerYear) {
        Objects.requireNonNull(periodIrr, "periodIrr");
        requirePositive(periodsPerYear);
        return BigDecimal.ONE.add(periodIrr).pow(periodsPerYear, MC).subtract(BigDecimal.ONE)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Simple annualization: {@code periodIrr × periodsPerYear}, at {@code 12} scales. */
    public static BigDecimal simpleAnnualize(BigDecimal periodIrr, int periodsPerYear) {
        Objects.requireNonNull(periodIrr, "periodIrr");
        requirePositive(periodsPerYear);
        return periodIrr.multiply(BigDecimal.valueOf(periodsPerYear))
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Modified IRR: positive flows compound to the horizon at the reinvestment rate, negative
     * flows discount from t0 at the finance rate, and the annualized bridge between the two is
     * the result. {@code null} when the flow set has both sides missing.
     */
    public static BigDecimal mirr(List<CashFlow> flows, BigDecimal reinvestRate,
            BigDecimal financeRate, int periodsPerYear) {
        requireFlows(flows);
        Objects.requireNonNull(reinvestRate, "reinvestRate");
        Objects.requireNonNull(financeRate, "financeRate");
        requirePositive(periodsPerYear);

        int horizon = 0;
        for (CashFlow flow : flows) {
            horizon = Math.max(horizon, flow.sequence());
        }
        if (horizon == 0) {
            return null;
        }
        BigDecimal reinvestPeriodic = reinvestRate.divide(BigDecimal.valueOf(periodsPerYear), MC);
        BigDecimal financePeriodic = financeRate.divide(BigDecimal.valueOf(periodsPerYear), MC);

        BigDecimal futureInflows = BigDecimal.ZERO;
        BigDecimal presentOutflows = BigDecimal.ZERO;
        for (CashFlow flow : flows) {
            BigDecimal amount = signed(flow);
            if (amount.signum() > 0) {
                futureInflows = futureInflows.add(amount
                        .multiply(BigDecimal.ONE.add(reinvestPeriodic)
                                .pow(horizon - flow.sequence(), MC), MC), MC);
            } else if (amount.signum() < 0) {
                presentOutflows = presentOutflows.add(amount.divide(
                        BigDecimal.ONE.add(financePeriodic).pow(flow.sequence(), MC), MC), MC);
            }
        }
        if (futureInflows.signum() <= 0 || presentOutflows.signum() >= 0) {
            return null;
        }
        BigDecimal ratio = futureInflows.divide(presentOutflows.negate(), MC);
        BigDecimal exponent = BigDecimal.valueOf(periodsPerYear)
                .divide(BigDecimal.valueOf(horizon), MC);
        return decimalPow(ratio, exponent)
                .subtract(BigDecimal.ONE)
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Whether the signed flow sequence changes sign exactly once. */
    public static boolean hasSingleSignChange(List<CashFlow> flows) {
        requireFlows(flows);
        int changes = 0;
        int previous = 0;
        for (CashFlow flow : flows) {
            int sign = signed(flow).signum();
            if (sign == 0) {
                continue;
            }
            if (previous != 0 && sign != previous) {
                changes++;
            }
            previous = sign;
        }
        return changes == 1;
    }

    private static boolean hasSignChange(List<CashFlow> flows) {
        int previous = 0;
        for (CashFlow flow : flows) {
            int sign = signed(flow).signum();
            if (sign == 0) {
                continue;
            }
            if (previous != 0 && sign != previous) {
                return true;
            }
            previous = sign;
        }
        return false;
    }

    /** INFLOW positive, OUTFLOW negative. */
    private static BigDecimal signed(CashFlow flow) {
        return flow.direction() == CashFlowDirection.INFLOW ? flow.amount() : flow.amount().negate();
    }

    /**
     * {@code base^exponent} for a non-negative exponent on a positive base: JDK 11 has no
     * BigDecimal decimal power, so it is computed as {@code exp(exponent · ln(base))} with series
     * at 40 significant digits — comfortably finer than the 34-digit solve precision.
     */
    private static BigDecimal decimalPow(BigDecimal base, BigDecimal exponent) {
        return exp(exponent.multiply(ln(base), CALC));
    }

    /** Σ z^(2n+1)/(2n+1); converges quickly for |z| ≤ 1/3. */
    private static BigDecimal atanhSeries(BigDecimal z) {
        BigDecimal sum = z;
        BigDecimal zSquared = z.multiply(z, CALC);
        BigDecimal term = z;
        for (int n = 1; n < 500; n++) {
            term = term.multiply(zSquared, CALC);
            BigDecimal part = term.divide(BigDecimal.valueOf(2L * n + 1), CALC);
            sum = sum.add(part, CALC);
            if (part.abs().compareTo(SERIES_STOP) < 0) {
                break;
            }
        }
        return sum;
    }

    /** ln(base) = 2·atanh((m−1)/(m+1)) + k·ln2 after reducing base to m·2^k, m ∈ [1, 2). */
    private static BigDecimal ln(BigDecimal base) {
        if (base.signum() <= 0) {
            throw new ArithmeticException("ln requires a positive argument: " + base.toPlainString());
        }
        BigDecimal m = base;
        int k = 0;
        while (m.compareTo(TWO) >= 0) {
            m = m.divide(TWO, CALC);
            k++;
        }
        while (m.compareTo(BigDecimal.ONE) < 0) {
            m = m.multiply(TWO, CALC);
            k--;
        }
        BigDecimal z = m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), CALC);
        BigDecimal logM = atanhSeries(z).multiply(TWO, CALC);
        return k == 0 ? logM
                : logM.add(LN2.multiply(BigDecimal.valueOf(k), CALC), CALC);
    }

    /** exp(y) = 2^k · Σ r^n/n! with y = k·ln2 + r, r kept near zero by the ln2 reduction. */
    private static BigDecimal exp(BigDecimal y) {
        int k = y.divide(LN2, 0, RoundingMode.HALF_EVEN).intValueExact();
        BigDecimal r = y.subtract(LN2.multiply(BigDecimal.valueOf(k), CALC), CALC);
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal term = BigDecimal.ONE;
        for (int n = 1; n < 200; n++) {
            term = term.multiply(r, CALC).divide(BigDecimal.valueOf(n), CALC);
            sum = sum.add(term, CALC);
            if (term.abs().compareTo(SERIES_STOP) < 0) {
                break;
            }
        }
        return k >= 0 ? sum.multiply(TWO.pow(k), CALC)
                : sum.divide(TWO.pow(-k), CALC);
    }

    private static void requireFlows(List<CashFlow> flows) {
        Objects.requireNonNull(flows, "flows");
        if (flows.isEmpty()) {
            throw new IllegalArgumentException("flows must not be empty");
        }
    }

    /** ln() needs a positive argument: a discount rate of -1 or lower has no present value. */
    private static void requirePositiveBase(BigDecimal growth, BigDecimal rate) {
        if (growth.signum() <= 0) {
            throw new IllegalArgumentException("rate must be greater than -1: " + rate);
        }
    }

    private static void requirePositive(int periodsPerYear) {
        if (periodsPerYear < 1) {
            throw new IllegalArgumentException("periodsPerYear must be at least 1: " + periodsPerYear);
        }
    }
}
