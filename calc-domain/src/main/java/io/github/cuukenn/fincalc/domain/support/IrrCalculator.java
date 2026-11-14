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
import java.util.ArrayList;
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
 * (no sign change — no root to converge on) is {@code null}, and the caller records the
 * {@code FCS-CALC-3003} warning — a metric is never fabricated. An exhausted budget is not a
 * refusal: the sign-opposite bracket invariant guarantees a root, so the solver returns the
 * bracket midpoint and the yield stands.
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
        return xnpv(flows, annualRate, yearWeights(flows));
    }

    /**
     * The same walk over day weights the caller already holds (an XIRR solve
     * evaluates this function ~120 times over the same flow set, and each evaluation used to redo
     * the origin scan and the 480 {@code days ÷ 365} quotients the flow set itself determines).
     *
     * <p>The weights come from {@link #yearWeights(List)} and nothing else, so the values are the
     * ones any single {@link #xnpv(List, BigDecimal)} call would derive — the same quotients in the
     * same order. What the seam saves is work, not digits.
     */
    public static BigDecimal xnpv(List<CashFlow> flows, BigDecimal annualRate,
            List<BigDecimal> yearWeights) {
        requireFlows(flows);
        Objects.requireNonNull(annualRate, "annualRate");
        Objects.requireNonNull(yearWeights, "yearWeights");
        if (yearWeights.size() != flows.size()) {
            throw new IllegalArgumentException("yearWeights must match flows: " + yearWeights.size()
                    + " weights for " + flows.size() + " flows");
        }
        BigDecimal growth = BigDecimal.ONE.add(annualRate);
        requirePositiveBase(growth, annualRate);
        // ln(1 + rate) is the same for every flow of one evaluation; DecimalMath.pow would take
        // its series flows.size() times. powWithLn is that power with the log factored out.
        BigDecimal lnGrowth = DecimalMath.ln(growth);
        BigDecimal total = BigDecimal.ZERO;
        for (int index = 0; index < flows.size(); index++) {
            BigDecimal factor = DecimalMath.powWithLn(growth, yearWeights.get(index), lnGrowth);
            total = total.add(signed(flows.get(index)).divide(factor, MC), MC);
        }
        return total;
    }

    /**
     * The {@code days ÷ 365} weight of every flow against the earliest flow date — a property of
     * the flow set alone, independent of any rate. One XIRR solve evaluates the day-discounted NPV
     * ~120 times; the weights are derived once for the solve instead of once per evaluation.
     */
    public static List<BigDecimal> yearWeights(List<CashFlow> flows) {
        requireFlows(flows);
        LocalDate origin = flows.get(0).date();
        for (CashFlow flow : flows) {
            if (flow.date().compareTo(origin) < 0) {
                origin = flow.date();
            }
        }
        List<BigDecimal> weights = new ArrayList<>(flows.size());
        for (CashFlow flow : flows) {
            weights.add(BigDecimal.valueOf(ChronoUnit.DAYS.between(origin, flow.date()))
                    .divide(DAYS_PER_YEAR, MC));
        }
        return weights;
    }

    /** Periodic yield with NPV zero; {@code null} when it cannot be found. */
    public static BigDecimal periodIrr(List<CashFlow> flows) {
        return periodIrr(flows, DEFAULT_SOLVER);
    }

    public static BigDecimal periodIrr(List<CashFlow> flows, RootSolver solver) {
        return periodIrr(flows, solver, RATE_LOWER, RATE_UPPER,
                BisectionNewtonSolver.DEFAULT_TOLERANCE);
    }

    /** 经 {@code NumericConfig} 提供求根区间、容差与迭代预算的重载（默认路径行为不变）。 */
    public static BigDecimal periodIrr(List<CashFlow> flows, RootSolver solver,
            BigDecimal lower, BigDecimal upper, BigDecimal tolerance) {
        requireFlows(flows);
        Objects.requireNonNull(solver, "solver");
        if (!hasSignChange(flows)) {
            return null;
        }
        return solver.solve(rate -> npv(flows, rate), lower, upper, tolerance);
    }

    /** 经 {@code NumericConfig} 提供求根参数的重载：区间、容差与迭代预算全部来自配置。 */
    public static BigDecimal periodIrr(List<CashFlow> flows, NumericConfig config) {
        return periodIrr(flows, new BisectionNewtonSolver(config.rootMaxIterations()),
                config.rootLowerBound(), config.rootUpperBound(), config.rootTolerance());
    }

    /** Annualized XIRR: the annual rate at which the day-discounted flows sum to zero. */
    public static BigDecimal xirr(List<CashFlow> flows) {
        return xirr(flows, DEFAULT_SOLVER);
    }

    public static BigDecimal xirr(List<CashFlow> flows, RootSolver solver) {
        return xirr(flows, solver, RATE_LOWER, RATE_UPPER,
                BisectionNewtonSolver.DEFAULT_TOLERANCE);
    }

    /** 经 {@code NumericConfig} 提供求根区间、容差与迭代预算的重载（默认路径行为不变）。 */
    public static BigDecimal xirr(List<CashFlow> flows, RootSolver solver,
            BigDecimal lower, BigDecimal upper, BigDecimal tolerance) {
        requireFlows(flows);
        Objects.requireNonNull(solver, "solver");
        if (!hasSignChange(flows)) {
            return null;
        }
        // The day weights belong to the flow set, not to the rate: derive them once for the whole
        // solve instead of ~120 times inside it. Same quotients, same order —
        // the root the solver lands on is the one the per-evaluation walk would have produced.
        List<BigDecimal> weights = yearWeights(flows);
        return solver.solve(rate -> xnpv(flows, rate, weights), lower, upper, tolerance);
    }

    /** 经 {@code NumericConfig} 提供求根参数的重载：区间、容差与迭代预算全部来自配置。 */
    public static BigDecimal xirr(List<CashFlow> flows, NumericConfig config) {
        return xirr(flows, new BisectionNewtonSolver(config.rootMaxIterations()),
                config.rootLowerBound(), config.rootUpperBound(), config.rootTolerance());
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
