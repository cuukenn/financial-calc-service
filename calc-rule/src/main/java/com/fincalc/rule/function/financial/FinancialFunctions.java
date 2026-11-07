package com.fincalc.rule.function.financial;

import com.fincalc.common.error.BusinessException;
import com.fincalc.domain.model.CashFlow;
import com.fincalc.domain.model.CashFlowDirection;
import com.fincalc.domain.model.CashFlowType;
import com.fincalc.domain.support.BisectionNewtonSolver;
import com.fincalc.domain.support.DecimalMath;
import com.fincalc.domain.support.IrrCalculator;
import com.fincalc.rule.expression.AstNode;
import com.fincalc.rule.expression.EvalContext;
import com.fincalc.rule.expression.FormulaError;
import com.fincalc.rule.expression.Value;
import com.fincalc.rule.function.FunctionRegistry;
import com.fincalc.rule.function.datex.DateSerialize;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The v1 financial family (design §8.5): 17 Excel-compatible functions with Excel's sign
 * conventions — money you pay out is negative — and Excel's error mapping, everything on
 * {@code BigDecimal}.
 *
 * <p><b>Reuse, and where the brief's reuse suggestion was adapted (documented per the ledger).</b>
 *
 * <ul>
 *   <li>{@code IRR} reuses {@link IrrCalculator#periodIrr} over {@link CashFlow}s built from the
 *       raw values: the calculator discounts flow at {@code sequence} from 0, so the root is
 *       exactly Excel's periodic IRR (an offsetting {@code (1+r)} factor does not move the
 *       root). The optional Excel {@code guess} argument is not expressible over the DSL's
 *       variadic scalar values (an extra number would be indistinguishable from a flow) and is
 *       not supported.</li>
 *   <li>{@code XIRR} reuses {@link IrrCalculator#xirr} (annual rate, days/365 from the earliest
 *       date — Excel semantics). Excel's two-range form cannot be expressed over scalar
 *       arguments, so this function takes alternating pairs: {@code XIRR(v1, d1, v2, d2, ...)}
 *       with date serials (a documented v1 deviation).</li>
 *   <li>{@code MIRR} reuses {@link IrrCalculator#mirr} with {@code periodsPerYear = 1}. Ledger
 *       R32 notes the calculator returns the ANNUAL rate when called with a real periods-per-year;
 *       with 1 it <em>is</em> the periodic rate, which is exactly what Excel's {@code MIRR}
 *       returns for its data frequency. The first value sits at t=0 and the rest at t=1..n−1,
 *       matching Excel.</li>
 *   <li>{@code RATE} reuses the {@link BisectionNewtonSolver} ({@code RootSolver} machinery) over
 *       the same bracket convention as {@link IrrCalculator} ({@code [-0.9999, 10]}); no sign
 *       change in the bracket is {@code #NUM!}.</li>
 *   <li>{@code PMT/PV/FV/IPMT/PPMT/NPER} are implemented directly from Excel's closed forms
 *       instead of delegating to {@code PmtSolver}: the solver returns the rent rounded to the
 *       shared money output scale (cents) and assumes end-of-period rents, which would corrupt
 *       Excel's full-precision, type-aware composition ({@code PV(PMT(...))} and the {@code IPMT}
 *       walk need unrounded values). Its golden rent agrees to the cent:
 *       {@code PMT(0.005, 12, -1000000) = 86066.43}.</li>
 *   <li>{@code NPV} is implemented directly with Excel's convention — the first value is
 *       discounted one full period ({@code Σ v_i/(1+r)^i, i from 1}) — which is deliberately
 *       <em>not</em> {@code IrrCalculator.npv}'s zero-based convention.</li>
 * </ul>
 *
 * <p>Every function is stateless and pure; failures surface as {@code ERROR} values.
 */
public final class FinancialFunctions {

    private static final MathContext MC = MathContext.DECIMAL128;

    /** Fixed anchor date for {@link CashFlow}s built from Excel-style value lists. */
    private static final LocalDate FLOW_DATE = LocalDate.of(1900, 1, 1);

    /** Bisection budget for {@code RATE}; the bracket needs ~40 halvings at the 1e-10 tolerance. */
    private static final int SOLVE_MAX_ITERATIONS = 100;

    private FinancialFunctions() {
    }

    /** Registers the 17 financial functions. */
    public static FunctionRegistry register(FunctionRegistry registry) {
        return registry
                .register(new Pmt())
                .register(new Ipmt())
                .register(new Ppmt())
                .register(new Pv())
                .register(new Fv())
                .register(new Rate())
                .register(new Nper())
                .register(new Npv())
                .register(new Irr())
                .register(new Xirr())
                .register(new Mirr())
                .register(new Effect())
                .register(new Nominal())
                .register(new Sln())
                .register(new Db())
                .register(new Ddb())
                .register(new Syd());
    }

    /** Template shared by every financial function: coerce failures into {@code ERROR} values. */
    private abstract static class Fn implements com.fincalc.rule.function.FormulaFunction {

        private final String code;
        private final int minArity;
        private final int maxArity;

        Fn(String code, int minArity, int maxArity) {
            this.code = code;
            this.minArity = minArity;
            this.maxArity = maxArity;
        }

        @Override
        public final String code() {
            return code;
        }

        @Override
        public final int minArity() {
            return minArity;
        }

        @Override
        public final int maxArity() {
            return maxArity;
        }

        @Override
        public final Value invoke(List<Value> args, EvalContext context) {
            try {
                return apply(args);
            } catch (BusinessException ex) {
                return AstNode.errorFrom(ex);
            } catch (ArithmeticException ex) {
                return Value.error(FormulaError.NUM_ERROR);
            }
        }

        abstract Value apply(List<Value> args);
    }

    private static BigDecimal num(List<Value> args, int index) {
        return args.get(index).asNumber();
    }

    private static int toInt(BigDecimal value) {
        return value.setScale(0, RoundingMode.DOWN).intValueExact();
    }

    /** Excel collapses any non-zero {@code type} argument to payments-in-advance. */
    private static int typeFlag(List<Value> args, int index) {
        return num(args, index).signum() != 0 ? 1 : 0;
    }

    private static void requirePositive(BigDecimal value) {
        if (value.signum() <= 0) {
            throw new BusinessException(FormulaError.NUM_ERROR);
        }
    }

    /**
     * {@code (1 + rate)^periods} at full precision; a rate at or below −1 has no growth base and
     * is Excel's {@code #NUM!}.
     */
    private static BigDecimal growth(BigDecimal rate, BigDecimal periods) {
        requirePositive(BigDecimal.ONE.add(rate));
        if (periods.signum() == 0) {
            return BigDecimal.ONE;
        }
        BigDecimal integral = periods.stripTrailingZeros();
        if (integral.scale() <= 0) {
            return BigDecimal.ONE.add(rate).pow(integral.intValueExact(), MC);
        }
        return DecimalMath.pow(BigDecimal.ONE.add(rate), periods);
    }

    /** Annuity factor {@code ((1+r)^n − 1)/r}, tending to {@code n} as {@code r → 0}. */
    private static BigDecimal annuity(BigDecimal rate, BigDecimal periods) {
        if (rate.signum() == 0) {
            return periods;
        }
        return growth(rate, periods).subtract(BigDecimal.ONE).divide(rate, MC);
    }

    /**
     * Level payment of the annuity equation
     * {@code pv·(1+r)^n + pmt·(1+r·type)·((1+r)^n − 1)/r + fv = 0} with Excel's signs: paying out
     * a received amount (negative {@code pv}) yields a positive payment.
     */
    private static BigDecimal pmtOf(BigDecimal rate, BigDecimal periods, BigDecimal pv,
            BigDecimal fv, int type) {
        requirePositive(periods);
        if (rate.signum() == 0) {
            return pv.add(fv).divide(periods, MC).negate();
        }
        BigDecimal factor = growth(rate, periods);
        BigDecimal numerator = pv.multiply(factor, MC).add(fv).multiply(rate, MC);
        BigDecimal denominator = factor.subtract(BigDecimal.ONE)
                .multiply(BigDecimal.ONE.add(rate.multiply(BigDecimal.valueOf(type), MC)), MC);
        return numerator.divide(denominator, MC).negate();
    }

    /** Excel's {@code FV(rate, nper, pmt, pv, type)} as a plain formula, used by {@code IPMT}. */
    private static BigDecimal fvOf(BigDecimal rate, BigDecimal periods, BigDecimal pmt,
            BigDecimal pv, int type) {
        if (periods.signum() == 0) {
            return pv.negate();
        }
        if (rate.signum() == 0) {
            return pv.add(pmt.multiply(periods, MC)).negate();
        }
        BigDecimal factor = growth(rate, periods);
        return pv.multiply(factor, MC)
                .add(pmt.multiply(BigDecimal.ONE.add(rate.multiply(BigDecimal.valueOf(type), MC)),
                        MC).multiply(annuity(rate, periods), MC))
                .negate();
    }

    /** Excel's {@code PV(rate, nper, pmt, fv, type)}. */
    private static BigDecimal pvOf(BigDecimal rate, BigDecimal periods, BigDecimal pmt,
            BigDecimal fv, int type) {
        if (rate.signum() == 0) {
            requirePositive(periods);
            return fv.add(pmt.multiply(periods, MC)).negate();
        }
        BigDecimal factor = growth(rate, periods);
        return fv.add(pmt.multiply(BigDecimal.ONE.add(rate.multiply(BigDecimal.valueOf(type), MC)),
                MC).multiply(annuity(rate, periods), MC)).divide(factor, MC).negate();
    }

    /**
     * Excel's {@code IPMT}: the interest portion of payment {@code per}. End-of-period payments
     * accrue {@code rate × FV(rate, per−1, pmt, pv, 0)}; payments in advance see no interest in
     * period 1 and accrue on the post-payment balance afterwards.
     */
    private static BigDecimal ipmtOf(BigDecimal rate, int per, BigDecimal periods, BigDecimal pv,
            BigDecimal fv, int type) {
        BigDecimal pmt = pmtOf(rate, periods, pv, fv, type);
        BigDecimal interestBase;
        if (type == 0) {
            interestBase = fvOf(rate, BigDecimal.valueOf(per - 1), pmt, pv, 0);
        } else if (per == 1) {
            return BigDecimal.ZERO;
        } else {
            interestBase = fvOf(rate, BigDecimal.valueOf(per - 2), pmt, pv, 1).subtract(pmt);
        }
        return interestBase.multiply(rate, MC);
    }

    /** Builds domain {@link CashFlow}s from Excel-style signed values at t=0,1,2,.... */
    private static List<CashFlow> flowsOf(List<BigDecimal> values) {
        List<CashFlow> flows = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            BigDecimal value = values.get(i);
            flows.add(new CashFlow(i, FLOW_DATE, value.abs(),
                    CashFlowType.RENT,
                    value.signum() < 0 ? CashFlowDirection.OUTFLOW : CashFlowDirection.INFLOW));
        }
        return flows;
    }

    /** A flow set with no sign change has no yield: Excel's {@code #NUM!}. */
    private static Value yieldOrNum(BigDecimal rate) {
        return rate == null
                ? Value.error(FormulaError.NUM_ERROR)
                : Value.number(rate);
    }

    /** {@code PMT(rate, nper, pv, [fv], [type])}. */
    private static class Pmt extends Fn {
        Pmt() {
            super("PMT", 3, 5);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal fv = args.size() > 3 ? num(args, 3) : BigDecimal.ZERO;
            int type = args.size() > 4 ? typeFlag(args, 4) : 0;
            return Value.number(pmtOf(num(args, 0), num(args, 1), num(args, 2), fv, type));
        }
    }

    /** {@code IPMT(rate, per, nper, pv, [fv], [type])}; per outside 1..nper is {@code #NUM!}. */
    private static class Ipmt extends Fn {
        Ipmt() {
            super("IPMT", 4, 6);
        }

        @Override
        Value apply(List<Value> args) {
            int per = toInt(num(args, 1));
            BigDecimal periods = num(args, 2);
            if (per < 1 || new BigDecimal(per).compareTo(periods) > 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            BigDecimal fv = args.size() > 4 ? num(args, 4) : BigDecimal.ZERO;
            int type = args.size() > 5 ? typeFlag(args, 5) : 0;
            return Value.number(ipmtOf(num(args, 0), per, periods, num(args, 3), fv, type));
        }
    }

    /** {@code PPMT(rate, per, nper, pv, [fv], [type])} = PMT − IPMT. */
    private static class Ppmt extends Fn {
        Ppmt() {
            super("PPMT", 4, 6);
        }

        @Override
        Value apply(List<Value> args) {
            int per = toInt(num(args, 1));
            BigDecimal periods = num(args, 2);
            if (per < 1 || new BigDecimal(per).compareTo(periods) > 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            BigDecimal fv = args.size() > 4 ? num(args, 4) : BigDecimal.ZERO;
            int type = args.size() > 5 ? typeFlag(args, 5) : 0;
            BigDecimal rate = num(args, 0);
            BigDecimal pv = num(args, 3);
            return Value.number(pmtOf(rate, periods, pv, fv, type)
                    .subtract(ipmtOf(rate, per, periods, pv, fv, type), MC));
        }
    }

    /** {@code PV(rate, nper, pmt, [fv], [type])}. */
    private static class Pv extends Fn {
        Pv() {
            super("PV", 3, 5);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal fv = args.size() > 3 ? num(args, 3) : BigDecimal.ZERO;
            int type = args.size() > 4 ? typeFlag(args, 4) : 0;
            return Value.number(pvOf(num(args, 0), num(args, 1), num(args, 2), fv, type));
        }
    }

    /** {@code FV(rate, nper, pmt, [pv], [type])}. */
    private static class Fv extends Fn {
        Fv() {
            super("FV", 3, 5);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal pv = args.size() > 3 ? num(args, 3) : BigDecimal.ZERO;
            int type = args.size() > 4 ? typeFlag(args, 4) : 0;
            return Value.number(fvOf(num(args, 0), num(args, 1), num(args, 2), pv, type));
        }
    }

    /**
     * {@code RATE(nper, pmt, pv, [fv], [type], [guess])} — solved by plain bisection over the
     * {@link IrrCalculator} bracket convention {@code [-0.9999, 10]} and the shared solver
     * tolerance (see {@link #bisect}); the equation is continuous with the {@code r → 0} limit
     * built into {@link #annuity}. {@code guess} is accepted for arity compatibility and ignored
     * (the bracket, not a seed, drives the search).
     */
    private static class Rate extends Fn {
        Rate() {
            super("RATE", 3, 6);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal periods = num(args, 0);
            requirePositive(periods);
            BigDecimal pmt = num(args, 1);
            BigDecimal pv = num(args, 2);
            BigDecimal fv = args.size() > 3 ? num(args, 3) : BigDecimal.ZERO;
            int type = args.size() > 4 ? typeFlag(args, 4) : 0;
            java.util.function.Function<BigDecimal, BigDecimal> equation =
                    rate -> pv.multiply(growth(rate, periods), MC)
                            .add(pmt.multiply(
                                    BigDecimal.ONE.add(rate.multiply(BigDecimal.valueOf(type), MC)),
                                    MC).multiply(annuity(rate, periods), MC), MC)
                            .add(fv, MC);
            BigDecimal root = bisect(equation, IrrCalculator.RATE_LOWER, IrrCalculator.RATE_UPPER,
                    BisectionNewtonSolver.DEFAULT_TOLERANCE);
            return yieldOrNum(root);
        }
    }

    /**
     * Plain bisection over a sign-opposite bracket — the numeric method behind {@code RATE}.
     *
     * <p><b>Why not the shared {@link BisectionNewtonSolver}.</b> Its Newton step estimates the
     * derivative numerically at {@code h = 1e-8·scale}; on the annuity equation the enormous
     * dynamic range of {@code pv·(1+r)^n} against {@code pmt·annuity(r)} makes that estimate land
     * the step just inside the current low edge, stalling the bracket (traced: the width froze at
     * ~0.84 while plain bisection passes it) until the budget exhausts and the solver reports
     * {@code null}. Plain bisection alone halves the bracket every step, so the ~11-wide bracket
     * reaches the {@code 1e-10} tolerance inside 40 iterations, far under the cap.
     *
     * @return the root, or {@code null} when the bracket holds no sign change
     */
    private static BigDecimal bisect(java.util.function.Function<BigDecimal, BigDecimal> equation,
            BigDecimal lower, BigDecimal upper, BigDecimal tolerance) {
        BigDecimal lo = lower.min(upper);
        BigDecimal hi = lower.max(upper);
        BigDecimal fLo = equation.apply(lo);
        BigDecimal fHi = equation.apply(hi);
        if (fLo.signum() == 0) {
            return lo;
        }
        if (fHi.signum() == 0) {
            return hi;
        }
        if (fLo.signum() == fHi.signum()) {
            return null;
        }
        for (int iteration = 0; iteration < SOLVE_MAX_ITERATIONS; iteration++) {
            BigDecimal mid = lo.add(hi).divide(BigDecimal.valueOf(2), MC);
            BigDecimal fMid = equation.apply(mid);
            if (fMid.abs().compareTo(tolerance) <= 0
                    || hi.subtract(lo).abs().compareTo(tolerance) <= 0) {
                return mid;
            }
            if (fMid.signum() == fLo.signum()) {
                lo = mid;
                fLo = fMid;
            } else {
                hi = mid;
            }
        }
        // Out of iterations: the bracket still holds the sign change; return its middle.
        return lo.add(hi).divide(BigDecimal.valueOf(2), MC);
    }

    /**
     * {@code NPER(rate, pmt, pv, [fv], [type])} — Excel's closed form
     * {@code ln((pmt·(1+r·type) − fv·r) / (pmt·(1+r·type) + pv·r)) / ln(1+r)}.
     */
    private static class Nper extends Fn {
        Nper() {
            super("NPER", 3, 5);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal rate = num(args, 0);
            BigDecimal pmt = num(args, 1);
            BigDecimal pv = num(args, 2);
            BigDecimal fv = args.size() > 3 ? num(args, 3) : BigDecimal.ZERO;
            int type = args.size() > 4 ? typeFlag(args, 4) : 0;
            if (rate.signum() == 0) {
                if (pmt.signum() == 0) {
                    return Value.error(FormulaError.NUM_ERROR);
                }
                return Value.number(pv.add(fv).divide(pmt, MC).negate());
            }
            requirePositive(BigDecimal.ONE.add(rate));
            BigDecimal level = pmt.multiply(BigDecimal.ONE.add(
                    rate.multiply(BigDecimal.valueOf(type), MC)), MC);
            BigDecimal denominator = level.add(pv.multiply(rate, MC), MC);
            if (denominator.signum() == 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            BigDecimal ratio = level.subtract(fv.multiply(rate, MC), MC).divide(denominator, MC);
            if (ratio.signum() <= 0) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            return Value.number(DecimalMath.ln(ratio).divide(DecimalMath.ln(BigDecimal.ONE.add(rate)),
                    MC));
        }
    }

    /**
     * {@code NPV(rate, value1, value2, ...)} — Excel's convention: the first value is discounted
     * one full period, {@code Σ v_i/(1+r)^i} from i = 1.
     */
    private static class Npv extends Fn {
        Npv() {
            super("NPV", 2, -1);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal rate = num(args, 0);
            requirePositive(BigDecimal.ONE.add(rate));
            BigDecimal factor = BigDecimal.ONE.add(rate);
            BigDecimal total = BigDecimal.ZERO;
            for (int i = 1; i < args.size(); i++) {
                total = total.add(num(args, i).divide(factor, MC), MC);
                factor = factor.multiply(BigDecimal.ONE.add(rate), MC);
            }
            return Value.number(total);
        }
    }

    /**
     * {@code IRR(value1, value2, ...)} — periodic yield via {@link IrrCalculator#periodIrr}; the
     * values sit at t=0,1,2,.... Excel's optional {@code guess} is not supported (see class doc).
     */
    private static class Irr extends Fn {
        Irr() {
            super("IRR", 2, -1);
        }

        @Override
        Value apply(List<Value> args) {
            List<BigDecimal> values = new ArrayList<>(args.size());
            for (Value arg : args) {
                values.add(arg.asNumber());
            }
            return yieldOrNum(IrrCalculator.periodIrr(flowsOf(values)));
        }
    }

    /**
     * {@code XIRR(value1, date1, value2, date2, ...)} — annual yield via
     * {@link IrrCalculator#xirr} (days/365 from the earliest date). The DSL has no range values,
     * so Excel's two ranges are flattened into alternating value/date-serial pairs (documented v1
     * deviation); an odd argument count is {@code #VALUE!}.
     */
    private static class Xirr extends Fn {
        Xirr() {
            super("XIRR", 4, -1);
        }

        @Override
        Value apply(List<Value> args) {
            if (args.size() % 2 != 0) {
                return Value.error(FormulaError.VALUE_ERROR);
            }
            List<CashFlow> flows = new ArrayList<>(args.size() / 2);
            for (int i = 0; i < args.size(); i += 2) {
                BigDecimal value = num(args, i);
                LocalDate date = DateSerialize.toLocalDate(num(args, i + 1));
                flows.add(new CashFlow(i / 2, date, value.abs(), CashFlowType.RENT,
                        value.signum() < 0 ? CashFlowDirection.OUTFLOW : CashFlowDirection.INFLOW));
            }
            return yieldOrNum(IrrCalculator.xirr(flows));
        }
    }

    /**
     * {@code MIRR(values..., finance_rate, reinvest_rate)} — the trailing two arguments are the
     * rates (distinguishing them from the values, which is why they are last), the rest are the
     * values at t=0,1,2,.... Returns the <em>periodic</em> rate like Excel by calling
     * {@link IrrCalculator#mirr} with {@code periodsPerYear = 1} (ledger R32: the calculator's
     * annualized contract only applies for a real periods-per-year).
     */
    private static class Mirr extends Fn {
        Mirr() {
            super("MIRR", 4, -1);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal financeRate = num(args, args.size() - 2);
            BigDecimal reinvestRate = num(args, args.size() - 1);
            List<BigDecimal> values = new ArrayList<>(args.size() - 2);
            for (int i = 0; i < args.size() - 2; i++) {
                values.add(num(args, i));
            }
            return yieldOrNum(IrrCalculator.mirr(flowsOf(values), reinvestRate, financeRate, 1));
        }
    }

    /** {@code EFFECT(nominal_rate, npery)} = (1 + nominal/npery)^npery − 1. */
    private static class Effect extends Fn {
        Effect() {
            super("EFFECT", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal nominal = num(args, 0);
            int npery = toInt(num(args, 1));
            if (nominal.signum() < 0 || npery < 1) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            BigDecimal periodic = nominal.divide(BigDecimal.valueOf(npery), MC);
            requirePositive(BigDecimal.ONE.add(periodic));
            return Value.number(growth(periodic, BigDecimal.valueOf(npery))
                    .subtract(BigDecimal.ONE));
        }
    }

    /** {@code NOMINAL(effect_rate, npery)} = npery·((1+effect)^(1/npery) − 1). */
    private static class Nominal extends Fn {
        Nominal() {
            super("NOMINAL", 2, 2);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal effect = num(args, 0);
            int npery = toInt(num(args, 1));
            if (effect.signum() < 0 || npery < 1) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            requirePositive(BigDecimal.ONE.add(effect));
            BigDecimal perPeriod = DecimalMath.pow(BigDecimal.ONE.add(effect),
                    BigDecimal.ONE.divide(BigDecimal.valueOf(npery), MC));
            return Value.number(BigDecimal.valueOf(npery).multiply(
                    perPeriod.subtract(BigDecimal.ONE), MC));
        }
    }

    /** {@code SLN(cost, salvage, life)} = (cost − salvage)/life. */
    private static class Sln extends Fn {
        Sln() {
            super("SLN", 3, 3);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal cost = num(args, 0);
            BigDecimal salvage = num(args, 1);
            BigDecimal life = num(args, 2);
            requirePositive(life);
            return Value.number(cost.subtract(salvage, MC).divide(life, MC));
        }
    }

    /**
     * {@code DB(cost, salvage, life, period, [month])} — fixed-declining balance with Excel's
     * three-decimal factor rounding, a partial first period for {@code month < 12} and an
     * optional {@code life+1} closing period.
     */
    private static class Db extends Fn {
        Db() {
            super("DB", 4, 5);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal cost = num(args, 0);
            BigDecimal salvage = num(args, 1);
            BigDecimal life = num(args, 2);
            int period = toInt(num(args, 3));
            int month = args.size() > 4 ? toInt(num(args, 4)) : 12;
            if (cost.signum() <= 0 || salvage.signum() < 0 || life.signum() < 1
                    || month < 1 || month > 12) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            int maxPeriod = month < 12 ? toInt(life) + 1 : toInt(life);
            if (period < 1 || period > maxPeriod) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            BigDecimal rate;
            if (salvage.signum() == 0) {
                rate = BigDecimal.ONE;
            } else {
                // Excel rounds the factor to three decimals.
                rate = BigDecimal.ONE.subtract(DecimalMath.pow(
                                salvage.divide(cost, MC),
                                BigDecimal.ONE.divide(life, MC)), MC)
                        .setScale(3, RoundingMode.HALF_UP);
            }
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal depreciation = BigDecimal.ZERO;
            for (int current = 1; current <= period; current++) {
                if (current == 1) {
                    depreciation = cost.multiply(rate, MC)
                            .multiply(BigDecimal.valueOf(month), MC)
                            .divide(BigDecimal.valueOf(12), MC);
                } else if (current == maxPeriod && month < 12) {
                    depreciation = cost.subtract(total, MC).multiply(rate, MC)
                            .multiply(BigDecimal.valueOf(12 - month), MC)
                            .divide(BigDecimal.valueOf(12), MC);
                } else {
                    depreciation = cost.subtract(total, MC).multiply(rate, MC);
                }
                total = total.add(depreciation, MC);
            }
            return Value.number(depreciation);
        }
    }

    /**
     * {@code DDB(cost, salvage, life, period, [factor])} — Excel's iterative double-declining
     * balance: each period takes the factor/life share of the remaining book value, capped at
     * what is left above the salvage.
     */
    private static class Ddb extends Fn {
        Ddb() {
            super("DDB", 4, 5);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal cost = num(args, 0);
            BigDecimal salvage = num(args, 1);
            BigDecimal life = num(args, 2);
            int period = toInt(num(args, 3));
            BigDecimal factor = args.size() > 4 ? num(args, 4) : BigDecimal.valueOf(2);
            if (cost.signum() < 0 || salvage.signum() < 0 || life.signum() < 1
                    || factor.signum() <= 0 || period < 1 || period > toInt(life)) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal depreciation = BigDecimal.ZERO;
            for (int current = 1; current <= period; current++) {
                BigDecimal remainingValue = cost.subtract(total, MC);
                BigDecimal depreciable = cost.subtract(salvage, MC).subtract(total, MC)
                        .max(BigDecimal.ZERO);
                depreciation = remainingValue.multiply(factor, MC).divide(life, MC)
                        .min(depreciable);
                total = total.add(depreciation, MC);
            }
            return Value.number(depreciation);
        }
    }

    /** {@code SYD(cost, salvage, life, per)} — sum-of-years'-digits depreciation. */
    private static class Syd extends Fn {
        Syd() {
            super("SYD", 4, 4);
        }

        @Override
        Value apply(List<Value> args) {
            BigDecimal cost = num(args, 0);
            BigDecimal salvage = num(args, 1);
            int life = toInt(num(args, 2));
            int per = toInt(num(args, 3));
            if (life < 1 || per < 1 || per > life) {
                return Value.error(FormulaError.NUM_ERROR);
            }
            BigDecimal depreciable = cost.subtract(salvage, MC);
            BigDecimal yearsLeft = BigDecimal.valueOf(life - per + 1);
            BigDecimal weight = BigDecimal.valueOf(2L)
                    .divide(BigDecimal.valueOf((long) life * (life + 1)), MC);
            return Value.number(depreciable.multiply(yearsLeft, MC).multiply(weight, MC));
        }
    }
}
