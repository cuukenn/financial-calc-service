package io.github.cuukenn.fincalc.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.model.CashFlowPerspective;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.CashFlowType;
import io.github.cuukenn.fincalc.domain.model.GracePeriod;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
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
import io.github.cuukenn.fincalc.rule.model.CompiledRuleSet;
import io.github.cuukenn.fincalc.rule.model.RuleRow;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Engine orchestration tests (Task 29). The brief's Step 1 tests are verbatim except for the two
 * bound corrections: the {@code TOTAL_RENT} golden is 1 032 797.15 (Task 21's authoritative
 * fixture: 11 × 86 066.43 plus the tail-adjusted 86 066.42) and the text blocks are rendered as
 * string concatenation because the build targets JDK 11. Two further tests pin the R52
 * effective-interest wiring (the table must close exactly for a BALLOON schedule and for a
 * SEPARATE schedule that carries a nonzero residual), one pins the loud RULE_DRIVEN-without-rule-set
 * failure and one pins the RULE_FALLBACK warning. R6: all fixtures are private helpers of this
 * class.
 */
class CalculationFacadeTest {

    // ---- Brief Step 1 tests ----

    @Test
    void routes_to_equal_installment_generator_and_enriches_metrics() {
        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(baseElement(), null, CalculationOptions.defaults()));

        assertThat(r.schedule().installments()).hasSize(12);
        FinancialMetrics m = r.schedule().header().metrics();
        assertThat(m.find(MetricCodes.TOTAL_PRINCIPAL)).isEqualByComparingTo("1000000.00");
        assertThat(m.find(MetricCodes.TOTAL_RENT)).isEqualByComparingTo("1032797.15");
        assertThat(m.find(MetricCodes.IRR_PERIOD)).isNotNull();
        assertThat(m.find(MetricCodes.XIRR)).isNotNull();
        assertThat(m.find(MetricCodes.DURATION_MACAULAY)).isNotNull();
        assertThat(r.schedule().cashFlows()).isNotEmpty();
        assertThat(r.schedule().issues()).noneMatch(i -> i.level() == IssueLevel.ERROR);
    }

    /**
     * R116 (Task 7): the metric-set invariant is enforced for <em>every</em> built-in, including
     * {@code BULLET_LOAN} and {@code EXPLICIT_RENT} — the two the loop used to skip. Every built-in
     * run goes through the same {@link io.github.cuukenn.fincalc.domain.metric.MetricRegistry}, so
     * the bag must carry the same 26 codes in the same insertion order whatever the method; a
     * generator may leave a code {@code null} (not computable), it may not drop one.
     */
    @Test
    void every_generator_produces_the_same_metric_set() {
        Set<String> expected = null;
        for (RepaymentMethodId method : List.of(RepaymentMethodId.EQUAL_INSTALLMENT,
                RepaymentMethodId.EQUAL_PRINCIPAL, RepaymentMethodId.INTEREST_FIRST,
                RepaymentMethodId.PRINCIPAL_FIRST, RepaymentMethodId.BULLET_LOAN,
                RepaymentMethodId.EXPLICIT_RENT)) {
            CalculationResult r = CalculationFacade.defaults().calculate(
                    new CalculationRequest(elementFor(method), null,
                            CalculationOptions.defaults()));
            // Every fixture amortizes: the §3.4 chain must stay clean for all six built-ins.
            assertThat(r.schedule().hasErrors()).isFalse();
            FinancialMetrics metrics = r.schedule().header().metrics();
            assertThat(metrics.codes()).hasSize(26);
            assertThat(metrics.find(MetricCodes.TOTAL_RENT)).isNotNull();
            if (expected == null) {
                expected = metrics.codes();
            } else {
                assertThat(metrics.codes()).isEqualTo(expected);
            }
        }
    }

    @Test
    void bullet_loan_defers_everything_to_maturity_and_passes_the_validators() {
        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(baseElement().toBuilder()
                        .method(RepaymentMethodId.BULLET_LOAN).build(), null,
                        CalculationOptions.defaults()));

        assertThat(r.schedule().installments()).hasSize(12);
        assertThat(r.schedule().issues()).noneMatch(i -> i.level() == IssueLevel.ERROR);
        // Zero-amount legs are dropped, so the schedule emits exactly one rent event.
        assertThat(r.schedule().cashFlows())
                .filteredOn(flow -> flow.type() == CashFlowType.RENT)
                .hasSize(1);
        assertThat(r.schedule().installments().get(11).principalDue())
                .isEqualByComparingTo("1000000.00");
        assertThat(r.schedule().installments().get(11).interestPaid())
                .isEqualByComparingTo("60000.00");
        assertThat(r.effectiveInterest()).isNotNull();
        assertThat(r.effectiveInterest().rows()).hasSize(12);
        assertThat(r.effectiveInterest().rows().get(11).closingNetInvestment())
                .isEqualByComparingTo("0.00");
    }

    /**
     * R103: the perspective is caller-selectable and reaches the metric run, while the schedule —
     * installments, rents and the lessor cash-flow table — is untouched (R109: the lessee view
     * drives only the {@code BORROWER_*} family).
     */
    @Test
    void a_lessee_request_publishes_the_borrower_family_and_leaves_the_schedule_alone() {
        assertThat(new CalculationRequest(baseElement(), null, null).perspective())
                .isEqualTo(CashFlowPerspective.LESSOR);
        assertThat(new CalculationRequest(baseElement(), null, null, null).perspective())
                .isEqualTo(CashFlowPerspective.LESSOR);

        CalculationResult lessor = CalculationFacade.defaults().calculate(
                new CalculationRequest(baseElement(), null, CalculationOptions.defaults()));
        CalculationResult lessee = CalculationFacade.defaults().calculate(
                new CalculationRequest(baseElement(), null, CalculationOptions.defaults(),
                        CashFlowPerspective.LESSEE));

        assertThat(lessor.schedule().header().metrics()
                .find(MetricCodes.BORROWER_TOTAL_REPAYMENT)).isNull();
        assertThat(lessee.schedule().header().metrics()
                .find(MetricCodes.BORROWER_TOTAL_REPAYMENT)).isEqualByComparingTo("1032797.15");
        assertThat(lessee.schedule().header().metrics().find(MetricCodes.BORROWER_IRR_PERIOD))
                .isEqualByComparingTo(lessee.schedule().header().metrics()
                        .find(MetricCodes.IRR_PERIOD));
        // No generator, cash-flow or validator behaviour changes: same rows, same event table.
        assertThat(lessee.schedule().installments()).hasSize(12);
        assertThat(lessee.schedule().header().metrics().find(MetricCodes.TOTAL_RENT))
                .isEqualByComparingTo("1032797.15");
        assertThat(lessee.schedule().cashFlows()).hasSameSizeAs(lessor.schedule().cashFlows());
        assertThat(lessee.schedule().cashFlows().get(0).direction()).isEqualTo(
                lessor.schedule().cashFlows().get(0).direction());
    }

    /**
     * A {@code BULLET_LOAN} schedule is the cleanest lessee case: zero intermediate rents are
     * dropped by the §9.4 builder, so the borrower view is one funded inflow and one maturity
     * outflow — and the borrower total is the single 1 060 000.00 settlement (1 000 000.00
     * principal + 60 000.00 interest).
     */
    @Test
    void a_bullet_loan_lessee_run_prices_the_single_maturity_repayment() {
        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(baseElement().toBuilder()
                        .method(RepaymentMethodId.BULLET_LOAN).build(), null,
                        CalculationOptions.defaults(), CashFlowPerspective.LESSEE));

        FinancialMetrics m = r.schedule().header().metrics();
        assertThat(m.find(MetricCodes.BORROWER_TOTAL_REPAYMENT))
                .isEqualByComparingTo("1060000.00");
        assertThat(m.find(MetricCodes.BORROWER_IRR_PERIOD)).isNotNull();
        assertThat(m.find(MetricCodes.BORROWER_IRR_ANNUAL)).isNotNull();
    }

    @Test
    void routes_to_rule_driven_when_rule_set_present() {
        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(baseElement().toBuilder()
                        .method(RepaymentMethodId.RULE_DRIVEN).build(), compiledRuleSet(),
                        CalculationOptions.defaults()));
        assertThat(r.schedule().installments()).hasSize(12);
    }

    @Test
    void effective_interest_table_available_when_irr_converges() {
        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(baseElement(), null, CalculationOptions.defaults()));
        assertThat(r.effectiveInterest()).isNotNull();
        assertThat(r.effectiveInterest().rows()).hasSize(12);
        assertThat(r.effectiveInterest().rows().get(11).closingNetInvestment())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void reports_missing_generator_as_business_error() {
        assertThatThrownBy(() -> new GeneratorRegistry().require("UNKNOWN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-ENG");
    }

    @Test
    void external_script_function_is_usable_end_to_end() throws IOException {
        Path dir = Files.createTempDirectory("fcs-fn");
        Files.writeString(dir.resolve("lease.fcs"), "VERSION 1\n"
                + "DEFINE 租金净额(RENT, VAT_RATE) = ROUND(RENT / (1 + VAT_RATE), 2)\n");
        CalculationFacade facade = CalculationFacade.defaultsWithScripts(dir);
        // The script function must live on the facade's own registry.
        assertThat(facade.functions().find("租金净额")).isNotNull();

        CalculationResult r = facade.calculate(new CalculationRequest(
                baseElement().toBuilder().method(RepaymentMethodId.RULE_DRIVEN).build(),
                ruleSetUsing("租金净额(1000, 0.06) > 0", facade),
                CalculationOptions.defaults()));

        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.schedule().header().metrics().find(MetricCodes.TOTAL_RENT)).isNotNull();
    }

    @Test
    void broken_script_fails_fast_at_startup() throws IOException {
        Path dir = Files.createTempDirectory("fcs-bad");
        Files.writeString(dir.resolve("bad.fcs"), "VERSION 1\nDEFINE A(X) = X +\n");
        assertThatThrownBy(() -> CalculationFacade.defaultsWithScripts(dir))
                .isInstanceOf(BusinessException.class);
    }

    // ---- R52: the effective-interest table closes for residual-bearing schedules ----

    @Test
    void effective_interest_table_closes_for_a_balloon_schedule() {
        LeaseElement element = baseElement().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.BALLOON_IN_RENT)
                .build();

        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(element, null, CalculationOptions.defaults()));

        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.effectiveInterest()).isNotNull();
        assertThat(r.effectiveInterest().rows()).hasSize(12);
        assertThat(r.effectiveInterest().rows().get(11).closingNetInvestment())
                .isEqualByComparingTo("100000.00");
    }

    @Test
    void effective_interest_table_closes_for_a_separate_residual_schedule() {
        LeaseElement element = baseElement().toBuilder()
                .residualValue(new BigDecimal("100000"))
                .residualHandling(ResidualHandling.SEPARATE)
                .build();

        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(element, null, CalculationOptions.defaults()));

        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.effectiveInterest()).isNotNull();
        assertThat(r.effectiveInterest().rows()).hasSize(12);
        assertThat(r.effectiveInterest().rows().get(11).closingNetInvestment())
                .isEqualByComparingTo("0.00");
    }

    // ---- Step 2 of the orchestration: RULE_DRIVEN without a rule set fails loudly ----

    @Test
    void rule_driven_without_rule_set_fails_loudly() {
        assertThatThrownBy(() -> CalculationFacade.defaults().calculate(
                new CalculationRequest(baseElement().toBuilder()
                        .method(RepaymentMethodId.RULE_DRIVEN).build(), null,
                        CalculationOptions.defaults())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FCS-ENG-1003");
    }

    // ---- Conditions (design §8.2 column D) drive period-specific rates end-to-end ----

    @Test
    void conditioned_rule_set_drives_period_specific_rates_end_to_end() {
        LeaseElement element = baseElement().toBuilder()
                .method(RepaymentMethodId.RULE_DRIVEN).build();
        // RATE carries the element's nominal rate 0.06, so the condition holds for periods 1..6;
        // periods 7..12 fall through to the unconditional lower-priority row.
        RuleRow guarded = builder("R1", 10, 1, 12).rate("0.03")
                .condition("PERIOD <= 6 && RATE > 0.04").build();
        CompiledRuleSet set = new RuleCompiler(FunctionRegistry.defaults()).compile(
                List.of(guarded, rateRow("R2", 20, 1, 12, "0.06")), element, false);

        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(element, set, CalculationOptions.defaults()));

        assertThat(r.schedule().hasErrors()).isFalse();
        assertThat(r.schedule().installments().get(0).appliedRate()).isEqualByComparingTo("0.03");
        assertThat(r.schedule().installments().get(5).appliedRate()).isEqualByComparingTo("0.03");
        assertThat(r.schedule().installments().get(6).appliedRate()).isEqualByComparingTo("0.06");
        assertThat(r.schedule().installments().get(11).appliedRate()).isEqualByComparingTo("0.06");
    }

    // ---- Diagnostics: fallback periods surface as WARN issues on the schedule ----

    @Test
    void rule_fallback_periods_are_reported_as_warnings() {
        CompiledRuleSet set = new RuleCompiler(FunctionRegistry.defaults()).compile(
                List.of(rateRow("R1", 10, 1, 6, "0.06")), baseElement(), true);
        CalculationResult r = CalculationFacade.defaults().calculate(new CalculationRequest(
                baseElement().toBuilder().method(RepaymentMethodId.RULE_DRIVEN).build(), set,
                new CalculationOptions(true,
                        io.github.cuukenn.fincalc.domain.model.CashFlowBasis.INCLUDE_FEES)));

        assertThat(r.schedule().installments()).hasSize(12);
        assertThat(r.schedule().issues())
                .filteredOn(i -> "FCS-ENG-1006".equals(i.code()))
                .hasSize(6)
                .allSatisfy(i -> assertThat(i.level()).isEqualTo(IssueLevel.WARN));
        assertThat(r.schedule().hasErrors()).isFalse();
    }

    /**
     * R118 (Task 7): the deferred-interest bucket under a day count that actually rounds. The
     * existing bullet fixtures all run THIRTY_360 monthly, where every period accrues exactly
     * {@code 5000.00} — a clean 2-dp figure, so nothing flows through the bucket as a tail. Under
     * ACT_360 the 31-day and 29-day periods accrue 5166.66̅ and 4833.33̅, each rounded to the cent
     * per P-06, and the year contains 366 actual days.
     *
     * <p>What this pins, and why it is not the same assertion as the 30/360 case:
     * <ol>
     *   <li>the maturity settlement is the sum of the <b>rounded</b> per-period accruals
     *       (61 000.02), not a re-derived term-exact figure (1 000 000 × 6% × 366/360 = 61 000.00);
     *       the deferral carries the rounding, it does not undo it;</li>
     *   <li>Σ {@code interestPaid} still equals Σ {@code interestAccrued} exactly, and the §3.4
     *       chain stays clean — the +0.02 tail is a disclosed excess over the term-exact accrual,
     *       visible in the period rows, not an absorbed discrepancy between the two columns;</li>
     *   <li>the balance tail reported by the header ({@code roundingDifference}) stays 0.00: the
     *       principal leg is untouched by the interest rounding, and the two tails are separate
     *       disclosures (P-09).</li>
     * </ol>
     */
    @Test
    void bullet_loan_defers_rounded_accruals_without_drift_under_act_360() {
        LeaseElement element = baseElement().toBuilder()
                .method(RepaymentMethodId.BULLET_LOAN)
                .dayCountCode("ACT_360")
                .build();

        CalculationResult r = CalculationFacade.defaults().calculate(
                new CalculationRequest(element, null, CalculationOptions.defaults()));

        List<Installment> rows = r.schedule().installments();
        assertThat(rows).hasSize(12);
        assertThat(r.schedule().hasErrors()).isFalse();

        // Rounding genuinely happened: 31 days × 6% / 360 = 5166.66̅ -> 5166.67, and 29 days
        // (2024 is a leap year) -> 4833.33. Both residues are sub-cent.
        assertThat(rows.get(0).days()).isEqualTo(31);
        assertThat(rows.get(0).interestAccrued()).isEqualByComparingTo("5166.67");
        assertThat(rows.get(1).days()).isEqualTo(29);
        assertThat(rows.get(1).interestAccrued()).isEqualByComparingTo("4833.33");
        assertThat(rows.subList(0, 11)).allSatisfy(row -> {
            assertThat(row.openingBalance()).isEqualByComparingTo("1000000.00");
            assertThat(row.interestPaid()).isEqualByComparingTo("0.00");
            assertThat(row.principalDue()).isEqualByComparingTo("0.00");
        });

        BigDecimal accruedSum = sum(rows, Installment::interestAccrued);
        BigDecimal paidSum = sum(rows, Installment::interestPaid);
        Installment last = rows.get(11);
        assertThat(paidSum).isEqualByComparingTo(accruedSum);
        assertThat(last.interestPaid()).isEqualByComparingTo("61000.02");
        assertThat(last.rentDue()).isEqualByComparingTo("1061000.02");
        assertThat(last.closingBalance()).isEqualByComparingTo("0.00");

        // The signed tail the deferral produces: rounded sum minus the term-exact accrual. It is
        // positive (+0.02) and disclosed by the metric, not absorbed.
        BigDecimal termExact = new BigDecimal("1000000").multiply(new BigDecimal("0.06"))
                .multiply(new BigDecimal("366")).divide(new BigDecimal("360"));
        assertThat(paidSum.subtract(termExact)).isEqualByComparingTo("0.02");
        assertThat(r.schedule().header().metrics().find(MetricCodes.TOTAL_INTEREST))
                .isEqualByComparingTo("61000.02");
        assertThat(r.schedule().header().roundingDifference()).isEqualByComparingTo("0.00");
    }

    private static BigDecimal sum(List<Installment> rows,
            java.util.function.Function<Installment, BigDecimal> field) {
        BigDecimal total = BigDecimal.ZERO;
        for (Installment row : rows) {
            total = total.add(field.apply(row));
        }
        return total;
    }

    // ---- Helpers (R6: per-class fixtures; mirror the calc-domain generator tests) ----

    /**
     * The 12-period rent sequence {@code EXPLICIT_RENT} consumes: the golden equal-installment
     * rents (11 × 86 066.43 plus the tail-adjusted 86 066.42). Fed back verbatim as an input, the
     * generator splits them without any last-period clamp (R69) and must land on the same zero
     * closing balance — which is what makes the metric-set loop above a clean run.
     */
    private static List<BigDecimal> explicitRentSequence() {
        List<BigDecimal> rents = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            rents.add(new BigDecimal("86066.43"));
        }
        rents.add(new BigDecimal("86066.42"));
        return rents;
    }

    /** Element for one built-in method; {@code EXPLICIT_RENT} additionally carries its rents. */
    private static LeaseElement elementFor(RepaymentMethodId method) {
        LeaseElement.Builder builder = baseElement().toBuilder().method(method);
        if (method == RepaymentMethodId.EXPLICIT_RENT) {
            builder.explicitRents(explicitRentSequence());
        }
        return builder.build();
    }

    private static LeaseElement baseElement() {
        return LeaseElement.builder()
                .principal(new BigDecimal("1000000"))
                .nominalRate(new BigDecimal("0.06"))
                .commenceDate(LocalDate.of(2024, 1, 1))
                .firstRentDate(LocalDate.of(2024, 2, 1))
                .periods(12)
                .frequency(PaymentFrequency.MONTHLY)
                .timing(PaymentTiming.IN_ARREARS)
                .dayCountCode("THIRTY_360")
                .dateRollCode("NONE")
                .rounding(RoundingPolicy.defaults())
                .residualValue(BigDecimal.ZERO)
                .residualHandling(ResidualHandling.SEPARATE)
                .deposit(BigDecimal.ZERO)
                .vatRate(new BigDecimal("0.06"))
                .method(RepaymentMethodId.EQUAL_INSTALLMENT)
                .gracePeriod(GracePeriod.none())
                .currency("CNY")
                .rateChangePolicy(RateChangePolicy.RESOLVE)
                .build();
    }

    /** An unconditional rate-only rule set covering all 12 periods. */
    private static RuleSetView compiledRuleSet() {
        return new RuleCompiler(FunctionRegistry.defaults()).compile(
                List.of(rateRow("R1", 10, 1, 12, "0.06")), baseElement(), false);
    }

    /**
     * A rule set whose highest-priority row guards its applicability with {@code condition} — an
     * expression calling {@code scriptCall}. The condition only parses when the facade's function
     * registry carries the external script function, so a green run proves the end-to-end script
     * path; the condition itself evaluates true (the generator builds the per-period
     * {@code EvalContext}), so R0 governs every period and the unconditional lower-priority row
     * R1 never fires.
     */
    private static RuleSetView ruleSetUsing(String condition, CalculationFacade facade) {
        RuleRow guarded = builder("R0", 5, 1, 12).rate("0.06").condition(condition).build();
        RuleRow fallback = rateRow("R1", 10, 1, 12, "0.06");
        return new RuleCompiler(facade.functions()).compile(List.of(guarded, fallback),
                baseElement().toBuilder().method(RepaymentMethodId.RULE_DRIVEN).build(), false);
    }

    private static RuleRow rateRow(String ruleId, int priority, int from, int to, String rate) {
        return builder(ruleId, priority, from, to).rate(rate).build();
    }

    /** Readable factory over the 18-arg {@link RuleRow} constructor (house rule R6). */
    private static RuleRowBuilder builder(String ruleId, int priority, int from, int to) {
        return new RuleRowBuilder(ruleId, priority, from, to);
    }

    private static final class RuleRowBuilder {
        private final String ruleId;
        private final int priority;
        private final int from;
        private final int to;
        private BigDecimal annualRate;
        private String condition;

        private RuleRowBuilder(String ruleId, int priority, int from, int to) {
            this.ruleId = ruleId;
            this.priority = priority;
            this.from = from;
            this.to = to;
        }

        RuleRowBuilder rate(String annualRate) {
            this.annualRate = new BigDecimal(annualRate);
            return this;
        }

        RuleRowBuilder condition(String condition) {
            this.condition = condition;
            return this;
        }

        RuleRow build() {
            return new RuleRow(ruleId, priority, from, to, annualRate, null,
                    PrincipalPlanKind.INHERIT, null, null,
                    io.github.cuukenn.fincalc.domain.model.InterestTreatmentKind.PAY_AS_INCURRED,
                    PaymentTiming.IN_ARREARS,
                    io.github.cuukenn.fincalc.domain.model.SpecialActionKind.NONE, null, 0, condition,
                    null, 0, null);
        }
    }
}
