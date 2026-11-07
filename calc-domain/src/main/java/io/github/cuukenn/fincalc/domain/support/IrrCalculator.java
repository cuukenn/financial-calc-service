package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.money.MoneyScale;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.port.RootSolver;
import java.math.BigDecimal;
import java.math.MathContext;
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
 *
 * <p><b>Precision.</b> These formulas keep guard digits: sums and quotients run at
 * {@link MathContext#DECIMAL128} and the power steps at 40 significant digits (see
 * {@link DecimalMath}), which is wider than the §8 scales of 10 / 12. Rounding an intermediate
 * money amount to 10 scales would quantise the {@code |f| < 1e-10} target that Newton is trying to
 * hit. Nothing here is rounded except the rate results of {@link #annualize}, {@link
 * #simpleAnnualize} and {@link #mirr}, at {@link MoneyScale#CALC}; the §9.5 metric scales
 * (XNPV at 2, IRR_PERIOD at 10) are pinned by the metric assembly task when it writes
 * {@link io.github.cuukenn.fincalc.domain.metric.FinancialMetrics}.
 *
 * <p><b>Multiple roots.</b> A flow set with several sign changes — fees plus balloon residual plus
 * a mid-term deposit refund is enough — has several rates that zero its NPV. These methods ask the
 * solver for a root of the whole bracket and may return any of them. §9.5's tie-break rule
 * 「取最接近名义利率的最小正根」 needs the element's nominal rate, which this class does not
 * receive, so it belongs to the metric assembly task; {@link #hasSingleSignChange(List)} is exposed
 * so that caller can tell when the answer is ambiguous.
 */
public final class IrrCalculator {

    /** Search bracket for periodic and annual yields. */
    public static final BigDecimal RATE_LOWER = new BigDecimal("-0.9999");
    public static final BigDecimal RATE_UPPER = new BigDecimal("10");

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final RootSolver DEFAULT_SOLVER = new BisectionNewtonSolver();

    private IrrCalculator() {
    }

    /** Net present value at a periodic rate, discounting each flow by its {@code sequence}. */
    public static BigDecimal npv(List<CashFlow> flows, BigDecimal periodRate) {
        requireFlows(flows);
        Objects.requireNonNull(periodRate, "periodRate");
        BigDecimal growth = BigDecimal.ONE.add(periodRate);
        requirePositiveBase(growth, periodRate);
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
            // BigDecimal has no decimal-exponent pow on JDK 11: see DecimalMath.
            BigDecimal factor = DecimalMath.pow(growth, years);
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
        return MoneyUtils.scale(BigDecimal.ONE.add(periodIrr).pow(periodsPerYear, MC)
                .subtract(BigDecimal.ONE), MoneyScale.CALC);
    }

    /** Simple annualization: {@code periodIrr × periodsPerYear}, at {@code 12} scales. */
    public static BigDecimal simpleAnnualize(BigDecimal periodIrr, int periodsPerYear) {
        Objects.requireNonNull(periodIrr, "periodIrr");
        requirePositive(periodsPerYear);
        return MoneyUtils.scale(periodIrr.multiply(BigDecimal.valueOf(periodsPerYear)),
                MoneyScale.CALC);
    }

    /**
     * Modified IRR: positive flows compound to the horizon at the reinvestment rate, negative flows
     * discount from t0 at the finance rate, and the bridge between the two is annualized over
     * {@code periodsPerYear} periods per year. Note the deliberate deviation from §9.5's literal
     * {@code (FV / −PV)^(1/n) − 1}: the exponent here is {@code periodsPerYear / horizon}, so the
     * result is the annual rate and a caller must not annualize it a second time. {@code null} when
     * either side of the flow set is missing.
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
        BigDecimal reinvestPeriodic = MoneyUtils.scale(
                reinvestRate.divide(BigDecimal.valueOf(periodsPerYear), MC), MoneyScale.CALC);
        BigDecimal financePeriodic = MoneyUtils.scale(
                financeRate.divide(BigDecimal.valueOf(periodsPerYear), MC), MoneyScale.CALC);

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
        return MoneyUtils.scale(DecimalMath.pow(ratio, exponent).subtract(BigDecimal.ONE),
                MoneyScale.CALC);
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

    /**
     * INFLOW positive, OUTFLOW negative: {@code CashFlow} carries the sign in its direction and its
     * amount stays positive, so a signed amount here would be negated twice and silently invert the
     * whole IRR (design document §9.4 review focus 6).
     */
    private static BigDecimal signed(CashFlow flow) {
        if (flow.amount().signum() < 0) {
            throw new IllegalStateException("cash flow amount must be positive: " + flow.amount()
                    + " (" + flow.type() + " " + flow.direction() + ")");
        }
        return flow.direction() == CashFlowDirection.INFLOW ? flow.amount() : flow.amount().negate();
    }

    private static void requireFlows(List<CashFlow> flows) {
        Objects.requireNonNull(flows, "flows");
        if (flows.isEmpty()) {
            throw new IllegalArgumentException("flows must not be empty");
        }
    }

    /** A discount rate of -1 or lower has no present value, and no positive base to take a power of. */
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
