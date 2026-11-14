package io.github.cuukenn.fincalc.testkit;

import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.PaymentFrequency;
import io.github.cuukenn.fincalc.domain.model.PaymentTiming;
import io.github.cuukenn.fincalc.domain.model.PrincipalPlanKind;
import io.github.cuukenn.fincalc.domain.model.RateChangePolicy;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RoundingPolicy;
import io.github.cuukenn.fincalc.domain.port.RuleSetView;
import io.github.cuukenn.fincalc.rule.compiler.RuleCompiler;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import io.github.cuukenn.fincalc.rule.model.RuleRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable lease elements and rule sets for golden tests and downstream consumers.
 *
 * <p>The default element is the Task 21 authoritative fixture: 12 monthly periods, principal
 * 1 000 000 at a 6% nominal rate, THIRTY_360, IN_ARREARS, no fees, deposit or residual — the
 * element every pinned golden number in {@link ReferenceTables} was produced from. All fixtures
 * use {@code IN_ARREARS} because the built-in generators reject {@code IN_ADVANCE} for
 * EQUAL_INSTALLMENT and INTEREST_FIRST (FCS-DOM-2004).
 */
public final class Fixtures {

    /** Principal of the authoritative fixture. */
    public static final BigDecimal DEFAULT_PRINCIPAL = new BigDecimal("1000000");

    /** Nominal annual rate of the authoritative fixture. */
    public static final BigDecimal DEFAULT_NOMINAL_RATE = new BigDecimal("0.06");

    /** Period count of the authoritative fixture. */
    public static final int DEFAULT_PERIODS = 12;

    private Fixtures() {
    }

    /** The authoritative fixture with the given repayment method and day-count code. */
    public static LeaseElement leaseElement(String method, String dayCountCode) {
        return leaseElement(DEFAULT_PRINCIPAL, DEFAULT_NOMINAL_RATE, DEFAULT_PERIODS,
                method, dayCountCode);
    }

    /**
     * The authoritative fixture shape with a different size, rate or term.
     *
     * @param principal    financed amount
     * @param annualRate   nominal annual rate as a fraction (0.06 = 6%)
     * @param periods      number of monthly periods
     * @param method       {@link RepaymentMethodId} name, e.g. {@code EQUAL_INSTALLMENT}
     * @param dayCountCode day-count code, e.g. {@code THIRTY_360} or {@code ACT_365F}
     */
    public static LeaseElement leaseElement(BigDecimal principal, BigDecimal annualRate,
            int periods, String method, String dayCountCode) {
        return LeaseElement.builder()
                .principal(principal)
                .nominalRate(annualRate)
                .commenceDate(LocalDate.of(2024, 1, 1))
                .firstRentDate(LocalDate.of(2024, 2, 1))
                .periods(periods)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode(dayCountCode)
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualValue(BigDecimal.ZERO)
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(BigDecimal.ZERO)
                .method(RepaymentMethodId.valueOf(method))
                .gracePeriod(GracePeriod.none())
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }

    /**
     * An unconditional rate-only rule set at the element's own nominal rate, covering every
     * period of the element. Exercise material for RULE_DRIVEN runs; the built-in methods read
     * it the same way and land on the same rate they would have used anyway.
     */
    public static RuleSetView ruleSet(LeaseElement element) {
        RuleRow rateRow = new RuleRow("FIXTURE_RATE", 10, 1, element.periods(),
                element.nominalRate(), null, PrincipalPlanKind.INHERIT, null, null,
                io.github.cuukenn.fincalc.domain.model.InterestTreatmentKind.PAY_AS_INCURRED,
                PaymentTiming.IN_ARREARS,
                io.github.cuukenn.fincalc.domain.model.SpecialActionKind.NONE, null, 0, null, null, 0, null);
        List<RuleRow> rows = new ArrayList<>();
        rows.add(rateRow);
        return new RuleCompiler(FunctionRegistry.defaults()).compile(rows, element, false);
    }

    /**
     * The two-segment rate rule set of the Plan 1.5 golden Table 9 (R72): unconditional rate
     * rows at 3% annual for periods 1–6 and 6% annual for periods 7–12. Segmentation material
     * for the built-in generators under a {@code RESOLVE} policy.
     */
    public static RuleSetView segmentedRateRuleSet(LeaseElement element) {
        List<RuleRow> rows = new ArrayList<>();
        rows.add(rateRow("SEG_R1", 10, 1, 6, "0.03"));
        rows.add(rateRow("SEG_R2", 20, 7, 12, "0.06"));
        return new RuleCompiler(FunctionRegistry.defaults()).compile(rows, element, false);
    }

    /** One unconditional rate-only rule row over the given period range. */
    private static RuleRow rateRow(String ruleId, int priority, int from, int to, String rate) {
        return new RuleRow(ruleId, priority, from, to, new BigDecimal(rate), null,
                PrincipalPlanKind.INHERIT, null, null,
                io.github.cuukenn.fincalc.domain.model.InterestTreatmentKind.PAY_AS_INCURRED,
                PaymentTiming.IN_ARREARS,
                io.github.cuukenn.fincalc.domain.model.SpecialActionKind.NONE, null, 0, null,
                null, 0, null);
    }

    /**
     * The equal-installment annuity rents of the Task 21 golden, given verbatim for the
     * {@code EXPLICIT_RENT} golden Table 10 (R69): eleven rents of 86 066.43 plus the
     * tail-adjusted 86 066.42 — the sequence that amortizes 1 000 000.00 at 6% THIRTY_360
     * exactly, so the given-rent split must reproduce the equal-installment schedule.
     */
    public static List<BigDecimal> explicitAnnuityRents() {
        List<BigDecimal> rents = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            rents.add(new BigDecimal("86066.43"));
        }
        rents.add(new BigDecimal("86066.42"));
        return rents;
    }
}
