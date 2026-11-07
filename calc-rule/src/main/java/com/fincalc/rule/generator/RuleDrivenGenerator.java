package com.fincalc.rule.generator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fincalc.common.error.BusinessException;
import com.fincalc.common.money.MoneyUtils;
import com.fincalc.domain.calc.CalculationContext;
import com.fincalc.domain.model.DomainErrorCode;
import com.fincalc.domain.model.InterestTreatmentKind;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.PaymentTiming;
import com.fincalc.domain.model.PeriodDate;
import com.fincalc.domain.model.PrincipalPlanKind;
import com.fincalc.domain.model.RepaymentMethodId;
import com.fincalc.domain.model.RuleOutcome;
import com.fincalc.domain.model.Schedule;
import com.fincalc.domain.model.SpecialActionKind;
import com.fincalc.domain.port.RuleSetView;
import com.fincalc.domain.support.AbstractAmortizationGenerator;
import com.fincalc.rule.RuleErrorCode;
import com.fincalc.rule.model.CompiledRule;
import com.fincalc.rule.model.CompiledRuleSet;
import com.fincalc.rule.model.RuleRow;

/**
 * Rule-driven schedule generator (design §8.7, Task 28): executes a compiled rule set period
 * by period, taking each period's rate and principal plan from the matched rule and falling
 * back to the element's built-in behaviour only where the rules say so.
 *
 * <p>The template base class ({@link AbstractAmortizationGenerator}) owns the period loop,
 * interest accrual and conservation check; this class owns rule resolution and principal
 * allocation. Resolution runs through {@link PlannedRuleSet}, a per-run view created in
 * {@link #generate(CalculationContext)} that binds the constructor's rule set to the run's
 * element and options:
 *
 * <ul>
 *   <li><strong>Uncovered period (R15, §8.7)</strong> — a period no rule matches is a blocking
 *       error by default: {@code FCS-RULE-2001} with the uncovered period named in the
 *       message. Only when {@link com.fincalc.domain.model.CalculationOptions#allowPartialRuleSet()}
 *       is on does the period fall back to the built-in equal-principal behaviour, and the
 *       installment's memo records the {@link #FALLBACK_MEMO} marker (the memo is how the
 *       sibling generators record per-installment provenance, and the WARN-level fact needs no
 *       {@code ValidationIssue} because the schedule itself stays valid).</li>
 *   <li><strong>Principal plans (§8.2.1)</strong> — {@code NONE} repays nothing;
 *       {@code FIXED} repays {@code principalValue}; {@code CUSTOM} repays the matching entry
 *       of the comma sequence; {@code EQUAL} (and a blank/INHERIT plan, which §8.2.1 defines as
 *       "the row only covers the rate") amortizes the remaining balance over the row's covered
 *       span with the last covered period absorbing the remainder; {@code PERCENT} amortizes
 *       the row's share of the contract principal the same way. Under the compiler-validated
 *       totals the share equals the remaining balance at the span's first covered period, so
 *       both use the balance actually left — which also keeps them correct when a lower-priority
 *       row interrupts a span and the span re-opens later.</li>
 *   <li><strong>INHERIT fallback</strong> — an explicit INHERIT plan falls back to the algorithm
 *       the element's {@code method()} names. v1 can delegate only to equal principal, i.e.
 *       {@code EQUAL_PRINCIPAL} and the rule-driven method itself; any other method raises the
 *       new {@code FCS-RULE-2007} (R57).</li>
 *   <li><strong>Last period</strong> — the final period is forced to repay whatever is still
 *       outstanding (net of the residual), so the schedule closes exactly (§8.2.1 末期取剩余).</li>
 *   <li><strong>R17 backstop</strong> — a plan that would push the balance negative (or repay
 *       more than the opening balance) raises {@code FCS-DOM-2003} instead of emitting a
 *       corrupt schedule; the compiled set is trusted but never at the cost of conservation.</li>
 * </ul>
 *
 * <p>Generators are stateless: all per-run state (the remaining principal pool, the open
 * amortization span) lives in the {@link PlannedRuleSet} created inside
 * {@link #generate(CalculationContext)}.
 */
public final class RuleDrivenGenerator extends AbstractAmortizationGenerator {

    /** Memo recorded on installments produced by an allowed partial-rule-set fallback (R15). */
    public static final String FALLBACK_MEMO = "RULE_FALLBACK";

    private final RuleSetView ruleSet;

    /**
     * Creates a generator bound to one compiled rule set. The set governs every run of this
     * generator instance; a rule set on the {@link CalculationContext} is not consulted.
     *
     * @param ruleSet the compiled rule set; never {@code null}
     */
    public RuleDrivenGenerator(RuleSetView ruleSet) {
        this.ruleSet = Objects.requireNonNull(ruleSet, "ruleSet");
    }

    @Override
    public String code() {
        return "RULE_DRIVEN";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public boolean supports(CalculationContext context) {
        return context.element().method() == RepaymentMethodId.RULE_DRIVEN
                && context.element().timing() == PaymentTiming.IN_ARREARS;
    }

    @Override
    protected void validate(LeaseElement element) {
        if (element.timing() != PaymentTiming.IN_ARREARS) {
            throw new BusinessException(DomainErrorCode.UNSUPPORTED_PAYMENT_TIMING,
                    element.timing());
        }
    }

    /**
     * Runs the template loop against a copy of the context whose rule set is the per-run
     * {@link PlannedRuleSet}. The copy is how the base loop — which resolves rates through
     * {@code context.ruleSet()} — is made to consume the constructor-bound set together with
     * the run's partial-rule-set option; the planning it performs needs the strict period
     * order the base loop guarantees.
     */
    @Override
    public Schedule generate(CalculationContext context) {
        Objects.requireNonNull(context, "context");
        int totalPeriods = periods(context).size();
        CalculationContext bound = new CalculationContext(context.element(),
                new PlannedRuleSet(ruleSet, context, totalPeriods), context.dayCount(),
                context.dateRoll(), context.clock(), context.options());
        return super.generate(bound);
    }

    @Override
    protected Allocation allocate(AllocationInput input, AmortizationState state) {
        RuleOutcome outcome = input.ruleOutcome();
        BigDecimal principal;
        if (input.lastPeriod()) {
            principal = money(input.remainingPrincipal()
                    .subtract(input.element().outstandingResidual()), input.element());
        } else if (outcome.principalPlan() == PrincipalPlanKind.NONE) {
            principal = MoneyUtils.ZERO;
        } else {
            principal = money(outcome.principalValue(), input.element());
        }
        if (principal.signum() < 0 || principal.compareTo(input.openingBalance()) > 0) {
            // R17 backstop: a rule plan may never drive the balance negative.
            throw new BusinessException(DomainErrorCode.NEGATIVE_PRINCIPAL_DUE,
                    input.periodNo(), principal.toPlainString());
        }
        requireSupportedAction(outcome, input);
        return new Allocation(principal, interestToPay(outcome, input), null, null,
                outcome.matched() ? outcome.ruleId() : FALLBACK_MEMO);
    }

    /**
     * Special-action guard (R65): v1 does not implement {@code FEE_AMORTIZE} or
     * {@code DEPOSIT_OFFSET} (they need actionParam parsing, a deposit ledger and defined
     * conservation interactions), and design §8.2.2 forbids silently ignoring a compiled
     * action — so a row carrying one fails generation with the compiler's own
     * {@code FCS-RULE-2005} code, naming the action and the period, mirroring the
     * {@link #interestToPay} posture. {@code NONE} passes through.
     */
    private static void requireSupportedAction(RuleOutcome outcome, AllocationInput input) {
        SpecialActionKind action = outcome.specialAction();
        if (action != null && action != SpecialActionKind.NONE) {
            throw new BusinessException(RuleErrorCode.UNSUPPORTED_SPECIAL_ACTION,
                    "规则动作 " + action + " 暂不支持（第 " + input.periodNo() + " 期）");
        }
    }

    /**
     * Interest actually paid this period. v1 implements the two single-period treatments with
     * unambiguous semantics — {@code PAY_AS_INCURRED} (the rows' default) pays the accrual,
     * {@code EXEMPT} pays nothing. The cross-period kinds ({@code CAPITALIZE}, {@code DEFER},
     * {@code SETTLE_AT_MATURITY}) have no §8-defined per-period semantics precise enough to
     * implement through the allocation hook, so they fail with the new {@code FCS-RULE-2008}
     * instead of being silently paid (R57: a code enters with the path that raises it).
     */
    private static BigDecimal interestToPay(RuleOutcome outcome, AllocationInput input) {
        InterestTreatmentKind treatment = outcome.interestTreatment();
        if (treatment == null || treatment == InterestTreatmentKind.PAY_AS_INCURRED) {
            return input.accruedInterest();
        }
        if (treatment == InterestTreatmentKind.EXEMPT) {
            return MoneyUtils.ZERO;
        }
        throw new BusinessException(RuleErrorCode.UNSUPPORTED_INTEREST_TREATMENT, treatment);
    }

    private static BigDecimal money(BigDecimal value, LeaseElement element) {
        return value.setScale(element.rounding().scale(), element.rounding().mode());
    }

    /**
     * The per-run rule view the base loop resolves against. It resolves the bound set one
     * period at a time (strictly in period order, as the template loop guarantees) and turns
     * each matched rule's plan into the concrete principal due that period, which it publishes
     * to {@code allocate} through the rewritten outcome's {@code principalValue}.
     *
     * <p>One instance per {@code generate} call, so its mutable pool/span state is safe: two
     * concurrent runs never share it.
     */
    private static final class PlannedRuleSet implements RuleSetView {

        private final RuleSetView delegate;
        private final LeaseElement element;
        private final int totalPeriods;
        private final boolean partialAllowed;
        /** ruleId -> {fromPeriod, toPeriod}; empty when the view exposes no compiled rows. */
        private final Map<String, int[]> spans;
        private final Map<String, List<BigDecimal>> sequences = new HashMap<>();
        /** Principal still to be repaid by the plans, net of the outstanding residual. */
        private BigDecimal pool;
        private String amortRuleId;
        private int amortTo;
        private BigDecimal amortPer;
        private BigDecimal amortBase;
        private int amortPaid;

        PlannedRuleSet(RuleSetView delegate, CalculationContext context, int totalPeriods) {
            this.delegate = delegate;
            this.element = context.element();
            this.totalPeriods = totalPeriods;
            this.partialAllowed = context.options().allowPartialRuleSet();
            this.spans = indexSpans(delegate);
            this.pool = money(element.principal(), element)
                    .subtract(money(element.outstandingResidual(), element));
        }

        private static Map<String, int[]> indexSpans(RuleSetView delegate) {
            if (!(delegate instanceof CompiledRuleSet)) {
                return Map.of();
            }
            Map<String, int[]> index = new HashMap<>();
            for (CompiledRule compiled : ((CompiledRuleSet) delegate).rules()) {
                RuleRow row = compiled.row();
                index.put(row.ruleId(), new int[] {row.fromPeriod(), row.toPeriod()});
            }
            return index;
        }

        @Override
        public RuleOutcome resolve(int periodNo, BigDecimal openingBalance, int days,
                BigDecimal defaultAnnualRate) {
            RuleOutcome outcome = delegate.resolve(periodNo, openingBalance, days,
                    defaultAnnualRate);
            if (outcome == null || !outcome.matched()) {
                return fallback(periodNo);
            }
            return plan(periodNo, outcome);
        }

        /**
         * An unmatched period is a blocking error by default (R15): {@code FCS-RULE-2001}
         * naming the uncovered period. With {@code allowPartialRuleSet} the period falls back
         * to the built-in equal-principal behaviour — the remaining balance spread over the
         * remaining periods — and is marked with the {@code RULE_FALLBACK} memo. The outcome
         * stays unmatched so the rate also falls back to the element's nominal rate.
         */
        private RuleOutcome fallback(int periodNo) {
            if (!partialAllowed) {
                throw new BusinessException(RuleErrorCode.INVALID_ROW_RANGE,
                        "第 " + periodNo + " 期未被规则集覆盖");
            }
            BigDecimal periodsLeft = BigDecimal.valueOf(totalPeriods - periodNo + 1);
            BigDecimal principal = money(pool.divide(periodsLeft, element.rounding().scale(),
                    element.rounding().mode()), element);
            pool = pool.subtract(principal);
            return new RuleOutcome(false, FALLBACK_MEMO, null, null, PrincipalPlanKind.FIXED,
                    principal, null, InterestTreatmentKind.PAY_AS_INCURRED,
                    SpecialActionKind.NONE, null);
        }

        /**
         * Turns the matched rule's principal plan into the concrete principal due this period
         * and rewrites the outcome to carry it. The plan kind, rate, treatment and action
         * columns pass through untouched.
         */
        private RuleOutcome plan(int periodNo, RuleOutcome outcome) {
            BigDecimal principal;
            switch (outcome.principalPlan()) {
                case NONE:
                    principal = MoneyUtils.ZERO;
                    break;
                case FIXED:
                    if (outcome.principalValue() == null) {
                        // Reachable: V5's deterministic-total check is skipped when an
                        // algorithm-driven row is present, so a valueless FIXED row can compile.
                        throw new BusinessException(RuleErrorCode.PRINCIPAL_PLAN_TOTAL,
                                "规则 " + outcome.ruleId() + " 为 FIXED 计划但缺少 principalValue");
                    }
                    principal = money(outcome.principalValue(), element);
                    break;
                case CUSTOM:
                    principal = customEntry(periodNo, outcome);
                    break;
                case EQUAL:
                case PERCENT:
                case INHERIT:
                    principal = amortizedPrincipal(periodNo, outcome);
                    break;
                default:
                    throw new IllegalStateException(
                            "unhandled principal plan: " + outcome.principalPlan());
            }
            pool = pool.subtract(principal);
            return new RuleOutcome(true, outcome.ruleId(), outcome.annualRate(),
                    outcome.rateBase(), outcome.principalPlan(), principal, null,
                    outcome.interestTreatment(), outcome.specialAction(), outcome.actionParam());
        }

        /** The CUSTOM entry for this period: the sequence's {@code (periodNo - from)}-th value. */
        private BigDecimal customEntry(int periodNo, RuleOutcome outcome) {
            int[] span = requireSpan(outcome);
            List<BigDecimal> sequence = sequences.computeIfAbsent(outcome.ruleId(),
                    id -> parseSequence(outcome.principalSequence(), id));
            return money(sequence.get(periodNo - span[0]), element);
        }

        /**
         * EQUAL / PERCENT / INHERIT allocation (§8.2.1): the balance still to be repaid when
         * the span opens, divided over the span's remaining covered periods, the last covered
         * period absorbing the cent remainder. An INHERIT plan means the built-in algorithm
         * decides — v1 delegates only to equal principal ({@code EQUAL_PRINCIPAL} and the
         * rule-driven method itself); any other method raises {@code FCS-RULE-2007}.
         */
        private BigDecimal amortizedPrincipal(int periodNo, RuleOutcome outcome) {
            if (!outcome.ruleId().equals(amortRuleId)) {
                openAmortSpan(periodNo, outcome);
            }
            BigDecimal principal = periodNo == amortTo
                    ? amortBase.subtract(amortPer.multiply(BigDecimal.valueOf(amortPaid)))
                    : amortPer;
            amortPaid++;
            return money(principal, element);
        }

        private void openAmortSpan(int periodNo, RuleOutcome outcome) {
            PrincipalPlanKind plan = outcome.principalPlan();
            if (plan == PrincipalPlanKind.INHERIT) {
                RepaymentMethodId method = element.method();
                if (method != RepaymentMethodId.EQUAL_PRINCIPAL
                        && method != RepaymentMethodId.RULE_DRIVEN) {
                    throw new BusinessException(RuleErrorCode.UNSUPPORTED_INHERIT_METHOD,
                            method);
                }
            }
            int[] span = requireSpan(outcome);
            // The covered periods still ahead of the loop: the full span on a fresh open, the
            // remaining tail when a lower-priority row interrupted and the span re-opens.
            int covered = span[1] - periodNo + 1;
            this.amortRuleId = outcome.ruleId();
            this.amortTo = span[1];
            this.amortBase = pool;
            this.amortPer = money(pool.divide(BigDecimal.valueOf(covered),
                    element.rounding().scale(), element.rounding().mode()), element);
            this.amortPaid = 0;
        }

        private int[] requireSpan(RuleOutcome outcome) {
            int[] span = spans.get(outcome.ruleId());
            if (span == null) {
                throw new IllegalStateException("rule set view does not expose the compiled row "
                        + "for plan " + outcome.principalPlan() + ": " + outcome.ruleId());
            }
            return span;
        }

        private static List<BigDecimal> parseSequence(String sequence, String ruleId) {
            Objects.requireNonNull(sequence, "principalSequence");
            List<BigDecimal> entries = new ArrayList<>();
            for (String part : sequence.split(",")) {
                entries.add(new BigDecimal(part.trim()));
            }
            if (entries.isEmpty()) {
                throw new IllegalStateException("empty principal sequence for rule " + ruleId);
            }
            return entries;
        }

        private static BigDecimal money(BigDecimal value, LeaseElement element) {
            return value.setScale(element.rounding().scale(), element.rounding().mode());
        }
    }
}
