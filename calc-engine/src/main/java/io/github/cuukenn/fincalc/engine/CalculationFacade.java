package io.github.cuukenn.fincalc.engine;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.money.MoneyScale;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.common.spi.ExtensionRegistry;
import io.github.cuukenn.fincalc.common.time.SystemClockProvider;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.calc.CalculationContext;
import io.github.cuukenn.fincalc.domain.metric.FinancialMetrics;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.metric.MetricContext;
import io.github.cuukenn.fincalc.domain.metric.MetricRegistry;
import io.github.cuukenn.fincalc.domain.metric.PeriodicRootHandoff;
import io.github.cuukenn.fincalc.domain.model.CashFlow;
import io.github.cuukenn.fincalc.domain.model.CashFlowDirection;
import io.github.cuukenn.fincalc.domain.model.CashFlowType;
import io.github.cuukenn.fincalc.domain.model.EffectiveInterestTable;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.ResidualHandling;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.domain.model.ProductProfile;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.port.CashFlowBuilder;
import io.github.cuukenn.fincalc.domain.port.DateRollConvention;
import io.github.cuukenn.fincalc.domain.port.DayCountConvention;
import io.github.cuukenn.fincalc.domain.support.DateRollConventions;
import io.github.cuukenn.fincalc.domain.support.DayCountConventions;
import io.github.cuukenn.fincalc.domain.support.EffectiveInterestCalculator;
import io.github.cuukenn.fincalc.domain.support.IrrCalculator;
import io.github.cuukenn.fincalc.domain.support.ValidatorChain;
import io.github.cuukenn.fincalc.domain.support.cashflow.StandardCashFlowBuilder;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
import io.github.cuukenn.fincalc.rule.generator.RuleDrivenGenerator;
import io.github.cuukenn.fincalc.rule.script.FunctionScriptLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The engine orchestration: turns a {@link CalculationRequest} into an enriched, validated
 * {@link CalculationResult}. Every prior module's API meets here; the facade owns no calculation
 * logic of its own beyond wiring and the effective-interest rate decision documented below.
 *
 * <p><b>The orchestration order (immutable — each step consumes the previous one's output):</b>
 * <ol>
 *   <li><b>Step 0 (construction)</b> — the {@link FunctionRegistry}: built-in
 *       {@link FunctionRegistry#defaults()} plus {@code classpath:functions/*.fcs} plus an external
 *       directory (only {@link #defaultsWithScripts(Path)} scans; a load failure fails startup, no
 *       degradation). With no scripts anywhere the registry is {@code defaults()} itself, so the
 *       definition-aware hash is stable.</li>
 *   <li><b>Step 1</b> — assemble the {@link CalculationContext}: the element's day-count and
 *       date-roll codes must resolve against the built-in conventions, or the run fails with
 *       {@link EngineErrorCode#CALCULATION_CONTEXT_INVALID}.</li>
 *   <li><b>Step 2</b> — {@link GeneratorRegistry#require}: route by {@code element.method()} to a
 *       generator and produce the raw schedule (no metrics, no cash flows). A
 *       {@code RULE_DRIVEN} method is routed through a per-run
 *       {@link RuleDrivenGenerator} bound to the request's rule set; without a rule set the run
 *       fails with {@link EngineErrorCode#RULE_DRIVEN_REQUIRES_RULE_SET}.</li>
 *   <li><b>Step 3</b> — {@link StandardCashFlowBuilder}: the §9.4 event table, lessor perspective,
 *       the request's cash-flow basis.</li>
 *   <li><b>Step 4</b> — {@link MetricRegistry#computeAll}: the §9.5 metric run over
 *       {@code MetricContext(flows, schedule, element, m, null, null, null, perspective)}. The rates
 *       are left unconfigured on purpose: {@code discountRate} is per-period and the metrics derive
 *       it from the run's own IRR; the MIRR reinvest/finance rates default inside the metric layer
 *       (R42/R44 — {@code CalculationOptions} carries no rate fields in v1, so nothing to pass).
 *       MIRR arrives already annual — nothing downstream annualizes it again (R32). The
 *       perspective is the request's (R103), defaulting to the lessor view; a {@code LESSEE} run
 *       reads the mirrored flow set, so the flow-driven §9.5 codes follow it ({@code NPV}/
 *       {@code XNPV} negate, the payback pair answers {@code null}, the IRR family keeps the same
 *       root) and the {@code BORROWER_*} family becomes computable. Nothing else reads it — the
 *       schedule, its lessor §9.4 event table and the validators are unchanged (R109/R124).</li>
 *   <li><b>Step 5</b> — {@link Schedule#with}: the schedule carries the metrics and the flows.</li>
 *   <li><b>Step 6</b> — {@link ValidatorChain#validate} plus the engine's own diagnostics: every
 *       finding is appended to the schedule's issues (the schedule is immutable — the enriched
 *       schedule is rebuilt once with the combined list). The engine emits WARN issues for
 *       metrics that threw ({@code FCS-ENG-1007}), for a flow set whose IRR root is therefore
 *       ambiguous or absent ({@code FCS-ENG-1005}, ruling R30 posture) and for
 *       partial-rule-set fallback periods ({@code FCS-ENG-1006}).</li>
 *   <li><b>Step 7</b> — the §9.7 effective-interest table; see the rate decision below. A missing
 *       rate or a table that cannot be built answers {@code null} with a WARN issue — never an
 *       exception, because IRR non-convergence is an expected outcome.</li>
 *   <li><b>Step 8</b> — return {@code CalculationResult(runId, schedule, effectiveInterest)};
 *       {@code runId} is {@code UUID.randomUUID()} without dashes.</li>
 * </ol>
 *
 * <p><b>The effective-interest rate decision (ledger ruling R52).</b> The §9.7 table prices a
 * chain that sees only what {@code cashReceived} pays — the rent legs — against the schedule's
 * opening balance, and must close at the schedule's own terminal balance (R47). The event-table
 * IRR ({@code IRR_PERIOD}) cannot feed that chain for a residual-bearing schedule: the §9.4
 * event table lists the residual as a maturity inflow one discount step past the last rent, a
 * leg {@code cashReceived} never receives, so pricing the chain with that root lands hundreds
 * short (Task 22) and the table raises {@code FCS-CALC-3006} on a perfectly valid schedule. The
 * facade therefore prices the table with the <b>rent-consistent periodic rate</b>: the IRR of the
 * exact stream the chain itself prices —
 * <ul>
 *   <li>{@code t0}: the schedule's first opening balance (the net investment, out);</li>
 *   <li>{@code t1..tn}: each installment's {@code rentDue} (in);</li>
 *   <li>{@code tn}: the terminal net investment re-dated to the last rent's index (in), when it is
 *       nonzero — the schedule's own closing balance (the ballooned 留购价) <b>plus the residual
 *       money the schedule never amortizes</b>: a separately settled 留购价, the guaranteed
 *       residual and the unguaranteed residual. CAS 21 第三十八条/第三十九条 allocates finance
 *       income over the lessor's net investment, and that investment unwinds into exactly those
 *       amounts at maturity, so pricing the chain without them (the pre-R147 wiring) priced a
 *       stream the deal does not have.</li>
 * </ul>
 * With that root the chain closes exactly for both shapes: the IRR equation
 * {@code P = Σ rentᵢ/(1+r)ⁱ + T/(1+r)ⁿ} rearranges to
 * {@code P(1+r)ⁿ − Σ rentᵢ(1+r)ⁿ⁻ⁱ = T}, which is precisely the chain's terminal condition
 * (opening compounded minus rents reinvested equals the target balance). Per-row cent rounding
 * and the rate's scale-10 truncation drift the computed closing by at most fractions of a cent
 * per period, inside the calculator's {@code 0.01 × periods} tolerance; the last row absorbs the
 * drift (R45's posture carries over: the interpolated tail leg may exceed 1 period, v1 keeps it
 * as approved). For a fee-free, deposit-free, fully-amortizing schedule the stream coincides with
 * the §9.4 event table, so the rent-consistent rate equals {@code IRR_PERIOD} there. Upfront fees
 * and deposits never enter the stream — the chain does not see them either
 * ({@code rowOneOpening} is the schedule's own opening balance), so fee-bearing schedules keep
 * closing.
 *
 * <p><b>One solve per stream (AGENTS.md §6 N-05).</b> When that stream is a flow set some solve of
 * this run already priced — the §9.4 event table {@code IRR_PERIOD} was solved over, or the
 * implicit-rate measure chain's table-plus-unguaranteed-residual set — the engine reads the run's
 * <em>unrounded</em> root off the {@link PeriodicRootHandoff} ledger instead of solving the same
 * problem twice. The raw root and not the published scale-10 {@code IRR_PERIOD} is what keeps every
 * §9.7 row cent-for-cent (AGENTS.md §6 N-04: the 0.01 元容差 is a validation tolerance, never a
 * licence to price a chain with a rounded rate). Where the streams differ — a separately settled
 * 留购价, a guaranteed residual listed on top of the balloon, the unguaranteed residual CAS 21
 * 第三十八条 prices into the net investment while §9.4 keeps the event table cash-only, an upfront
 * fee or a deposit leg — the chain solves its own root: a different stream has a different root,
 * and that stated caliber difference is the only reason a run pays for a second solve.
 * {@link MetricCodes#LEASE_IMPLICIT_RATE} shares the same ledger: when its stream is the event
 * table (no unguaranteed residual, asset value = principal) it reads {@code IRR_PERIOD}'s root, and
 * when it solves its own it records it, so the §9.7 chain reuses that root instead of solving the
 * identical stream a third time.
 *
 * <p>If the stream has no root ({@code periodIrr == null}) or the calculator still refuses
 * the table, {@code effectiveInterest} is {@code null} and the failure surfaces as a WARN issue
 * carrying the refused code — never an exception.
 *
 * <p><b>Known v1 scope notes.</b> The multi-root tie-break「取最接近名义利率的最小正根」is not
 * implemented: it needs {@code element.nominalRate()} inside the solver (R30); the facade keeps
 * {@link IrrCalculator#hasSingleSignChange} as the ambiguity signal and reports
 * {@code FCS-ENG-1005} instead. Deferred/unpaid interest is not surfaced on {@link Schedule}
 * (Task 16 note) and the rule rows' {@code rateBase} column is carried but not implemented
 * (Task 28 note) — both stay tracked. Instances are immutable and safe to share.
 */
public final class CalculationFacade {

    /** Classpath prefix the startup wiring scans for {@code .fcs} function scripts. */
    private static final String CLASSPATH_SCRIPT_PREFIX = "functions";

    private final FunctionRegistry functions;
    private final GeneratorRegistry generators;
    private final MetricRegistry metrics;
    private final ValidatorChain validators;
    /** The mortgage chain (§2.3 threshold disclosures, A2) — selected per run by the product. */
    private final ValidatorChain mortgageValidators;
    private final CashFlowBuilder cashFlowBuilder;

    private CalculationFacade(FunctionRegistry functions) {
        this.functions = Objects.requireNonNull(functions, "functions");
        this.generators = GeneratorRegistry.defaults();
        this.metrics = MetricRegistry.defaults();
        this.validators = ValidatorChain.defaults();
        this.mortgageValidators = ValidatorChain.withMortgageThresholds();
        this.cashFlowBuilder = new StandardCashFlowBuilder();
    }

    /**
     * A facade over the built-in function library only — no classpath or external scripts.
     *
     * @return a facade whose rule expressions may call the built-in functions
     */
    public static CalculationFacade defaults() {
        return defaults(FunctionRegistry.defaults());
    }

    /**
     * A facade over the given function library.
     *
     * @param functions the library the {@code RuleCompiler} and script loading build on; never
     *                  {@code null}
     * @return a facade carrying the given library
     */
    public static CalculationFacade defaults(FunctionRegistry functions) {
        return new CalculationFacade(functions);
    }

    /**
     * The startup wiring of orchestration step 0: built-in functions, then every
     * {@code classpath:functions/*.fcs} resource, then every {@code *.fcs} file of the external
     * directory (sorted by file name, later definitions override earlier ones), frozen before any
     * calculation runs.
     *
     * <p>Fail-fast by contract: a script that does not parse keeps its loader's {@code FCS-RULE-*}
     * failure and the startup dies — no degradation to the built-in library. With no scripts
     * anywhere the resulting registry is the built-in library itself, so its hash is stable.
     * Disclosed v1 limitation (Task 27): classpath enumeration covers exploded {@code file:}
     * directories only; resources inside jars are not enumerated.
     *
     * @param externalDir the directory the external {@code .fcs} scripts are loaded from; never
     *                    {@code null}
     * @return a facade carrying the frozen registry
     * @throws BusinessException with the loader's {@code FCS-RULE-*} code when a script is broken,
     *                           or {@link EngineErrorCode#FUNCTION_SCRIPT_DIR_LOAD_FAILED} when the
     *                           directory cannot be read
     */
    public static CalculationFacade defaultsWithScripts(Path externalDir) {
        Objects.requireNonNull(externalDir, "externalDir");
        FunctionRegistry functions = FunctionScriptLoader.loadClasspath(CLASSPATH_SCRIPT_PREFIX,
                FunctionRegistry.defaults());
        functions = FunctionScriptLoader.load(listScripts(externalDir), functions);
        return defaults(functions);
    }

    /** The function library this facade compiles rule expressions and scripts against. */
    public FunctionRegistry functions() {
        return functions;
    }

    /**
     * Runs the full orchestration over {@code request}; see the class javadoc for the step order.
     *
     * @param request the run's element, rule set and options; never {@code null}
     * @return the enriched, validated result; {@code effectiveInterest()} is nullable
     */
    public CalculationResult calculate(CalculationRequest request) {
        Objects.requireNonNull(request, "request");
        LeaseElement element = request.element();

        // Step 1: the calculation context (口径/滚动缺失 → FCS-ENG-1001).
        DayCountConvention dayCount = requireConvention(DayCountConventions.all(),
                element.dayCountCode(), "dayCount");
        DateRollConvention dateRoll = requireConvention(DateRollConventions.all(),
                element.dateRollCode(), "dateRoll");

        // Step 2: route to the generator and produce the raw schedule.
        GeneratorRegistry generators = this.generators;
        if (element.method() == RepaymentMethodId.RULE_DRIVEN) {
            if (request.ruleSet() == null) {
                throw new BusinessException(EngineErrorCode.RULE_DRIVEN_REQUIRES_RULE_SET);
            }
            generators = generators.withRuleDriven(request.ruleSet());
        }
        CalculationContext context = new CalculationContext(element, request.ruleSet(), dayCount,
                dateRoll, SystemClockProvider.INSTANCE, request.options());
        Schedule schedule = generators.require(element.method().name()).generate(context);

        // Step 3: the §9.4 event table.
        List<CashFlow> flows = cashFlowBuilder.build(schedule, element, request.options());

        // Step 4: the §9.5 metric run (rates unconfigured — derived inside the metric layer). The
        // perspective is the caller's (R103); it changes nothing above, and the schedule keeps the
        // lessor event table whatever the metric view is (R109).
        List<String> failedMetrics = new ArrayList<>();
        MetricContext metricContext = new MetricContext(flows, schedule, element,
                periodsPerYear(element), null, null, null, request.perspective());
        FinancialMetrics metrics = this.metrics.computeAll(metricContext, failedMetrics);

        // Step 5: the enriched schedule.
        schedule = schedule.with(metrics, flows);

        // Step 6: validation plus the engine's own diagnostics.
        List<ValidationIssue> engineIssues = new ArrayList<>();
        // A1（form-field-gaps 15）：产品身份只在这里生效——选校验链。测算内核保持
        // 产品盲（R100）；LEASE / 未声明产品的 run 走缺省链，issues 与 v1 逐字节一致。
        schedule = (request.options().product() == ProductProfile.MORTGAGE
                ? mortgageValidators : validators).validate(schedule, element);
        for (String code : failedMetrics) {
            engineIssues.add(new ValidationIssue(IssueLevel.WARN,
                    EngineErrorCode.METRIC_COMPUTATION_FAILED.code(),
                    String.format(EngineErrorCode.METRIC_COMPUTATION_FAILED.message(), code),
                    null));
        }
        if (element.frequency().periodsPerYear() == 0) {
            // Not a failure: no periods-per-year exists to annualize by, so the annualizing
            // metrics answered null by construction. Say so once, in the run, instead of leaving
            // the reader to count which cards came back empty.
            engineIssues.add(new ValidationIssue(IssueLevel.WARN,
                    EngineErrorCode.ANNUAL_METRICS_NOT_APPLICABLE.code(),
                    String.format(EngineErrorCode.ANNUAL_METRICS_NOT_APPLICABLE.message(),
                            element.frequency().name()),
                    null));
        }
        if (!IrrCalculator.hasSingleSignChange(flows)) {
            engineIssues.add(new ValidationIssue(IssueLevel.WARN,
                    EngineErrorCode.IRR_ROOT_AMBIGUOUS.code(),
                    EngineErrorCode.IRR_ROOT_AMBIGUOUS.message(), null));
        }
        for (Installment installment : schedule.installments()) {
            if (RuleDrivenGenerator.isFallbackMemo(installment.memo())) {
                engineIssues.add(new ValidationIssue(IssueLevel.WARN,
                        EngineErrorCode.RULE_FALLBACK_PERIOD.code(),
                        String.format(EngineErrorCode.RULE_FALLBACK_PERIOD.message(),
                                installment.periodNo()), installment.periodNo()));
            }
        }

        // Step 7: the §9.7 effective-interest table (nullable, never thrown).
        EffectiveInterestTable effectiveInterest = effectiveInterest(schedule, element, metrics,
                metricContext.rootHandoff(), engineIssues);

        // The engine's findings join the schedule's own issues in one rebuild.
        if (!engineIssues.isEmpty()) {
            List<ValidationIssue> combined = new ArrayList<>(schedule.issues());
            combined.addAll(engineIssues);
            schedule = new Schedule(schedule.header(), schedule.installments(),
                    schedule.cashFlows(), combined);
        }

        // Step 8: the result.
        return new CalculationResult(UUID.randomUUID().toString().replace("-", ""), schedule,
                effectiveInterest);
    }

    /**
     * Step 7: the §9.7 table at the rent-consistent rate (see the class javadoc for the rate
     * decision). Every refusal path answers {@code null} and records a WARN issue carrying the
     * stable code of the refusal — the run itself never fails here.
     */
    private EffectiveInterestTable effectiveInterest(Schedule schedule, LeaseElement element,
            FinancialMetrics metrics, PeriodicRootHandoff rootHandoff, List<ValidationIssue> issues) {
        if (metrics.find(MetricCodes.IRR_PERIOD) == null) {
            issues.add(new ValidationIssue(IssueLevel.WARN, CalcErrorCode.IRR_NOT_CONVERGED.code(),
                    CalcErrorCode.IRR_NOT_CONVERGED.code()
                            + ": IRR_PERIOD 不收敛, 实际利率法摊销表未生成", null));
            return null;
        }
        BigDecimal residualExtra = unrecoveredResiduals(element, schedule);
        BigDecimal periodIrr = netInvestmentPeriodIrr(schedule, residualExtra, rootHandoff);
        if (periodIrr == null) {
            issues.add(new ValidationIssue(IssueLevel.WARN, CalcErrorCode.IRR_NOT_CONVERGED.code(),
                    CalcErrorCode.IRR_NOT_CONVERGED.code()
                            + ": 租金口径现金流无内部收益率, 实际利率法摊销表未生成", null));
            return null;
        }
        try {
            return EffectiveInterestCalculator.calculate(schedule, periodIrr, residualExtra);
        } catch (BusinessException ex) {
            issues.add(new ValidationIssue(IssueLevel.WARN, ex.errorCode().code(),
                    "实际利率法摊销表未生成: " + ex.getMessage(), null));
            return null;
        } catch (ArithmeticException ex) {
            // BigDecimal's own arithmetic failures (non-terminating quotients, intValueExact
            // overflows) are numeric-degenerate inputs, not engine bugs — the "never fails here"
            // contract covers them the same way: a WARN and no table, never a broken run.
            issues.add(new ValidationIssue(IssueLevel.WARN, CalcErrorCode.IRR_NOT_CONVERGED.code(),
                    "实际利率法摊销表未生成: 数值退化 (" + ex.getMessage() + ")", null));
            return null;
        }
    }

    /**
     * The terminal net investment the §9.7 chain must stop at (CAS 21 第三十八条 / 第三十九条):
     * the schedule's own closing balance (the ballooned 留购价) plus the money the schedule never
     * amortizes — a separately settled 留购价, the guaranteed residual, and the unguaranteed
     * residual, which the lessor recovers by keeping the asset rather than as cash. The rate
     * search prices exactly this target, which is what makes the chain close (R52's identity).
     */
    private static BigDecimal unrecoveredResiduals(LeaseElement element, Schedule schedule) {
        if (element == null) {
            return MoneyUtils.ZERO;
        }
        BigDecimal separateOption = element.residualHandling() == ResidualHandling.SEPARATE
                ? MoneyUtils.zeroIfNull(element.residualValue()) : MoneyUtils.ZERO;
        return separateOption
                .add(MoneyUtils.zeroIfNull(element.guaranteedResidual()))
                .add(MoneyUtils.zeroIfNull(element.unguaranteedResidual()));
    }

    /**
     * The net-investment periodic rate of the R52 wiring (renamed once the terminal target learned
     * about residual money): the IRR of the stream the §9.7 chain itself prices — the first opening
     * balance at t0, the rent legs, and the terminal net investment re-dated to the last rent's
     * index when nonzero. See the class javadoc for the closing arithmetic and the shapes this
     * closes exactly.
     */
    private BigDecimal netInvestmentPeriodIrr(Schedule schedule, BigDecimal residualExtra,
            PeriodicRootHandoff rootHandoff) {
        List<Installment> installments = schedule.installments();
        if (installments.isEmpty()) {
            return null;
        }
        List<CashFlow> stream = new ArrayList<>(installments.size() + 1);
        Installment first = installments.get(0);
        stream.add(new CashFlow(0, first.startDate(), first.openingBalance(), CashFlowType.PRINCIPAL,
                CashFlowDirection.OUTFLOW));
        for (Installment installment : installments) {
            BigDecimal rent = installment.rentDue();
            // A zero rent carries no cash and a negative one is a broken schedule the validators
            // already flag; neither may enter the root search.
            if (rent != null && rent.signum() > 0) {
                stream.add(new CashFlow(installment.periodNo(), installment.endDate(), rent,
                        CashFlowType.RENT, CashFlowDirection.INFLOW));
            }
        }
        Installment last = installments.get(installments.size() - 1);
        BigDecimal terminal = MoneyUtils.scale(
                last.closingBalance().add(MoneyUtils.zeroIfNull(residualExtra)), MoneyScale.OUTPUT);
        if (terminal.signum() != 0) {
            stream.add(new CashFlow(last.periodNo(), last.endDate(), terminal,
                    CashFlowType.RESIDUAL, CashFlowDirection.INFLOW));
        }

        BigDecimal solvedOverRun = rootHandoff.rootFor(stream);
        if (solvedOverRun != null) {
            // The stream this chain prices is the very stream some solve of this run — the §9.5
            // metric line's IRR_PERIOD, or the implicit-rate measure chain — already solved, or its
            // exact direction mirror (R103): same discount indices, same signed amounts, so it has
            // the same root, and solving it again is the duplicate AGENTS.md §6 N-05 forbids. Read
            // the ledger's unrounded root, not the published scale-10 IRR_PERIOD: substituting the
            // rounded rate would move cents along the chain's rows (AGENTS.md §6 N-04).
            return solvedOverRun;
        }
        // A stated second solve, not a silent one. This stream differs from the §9.4 event table the
        // run's IRR_PERIOD is a root of whenever the deal carries money the event table does not list
        // in the same place: an upfront fee or deposit leg, a separately settled 留购价, a guaranteed
        // residual on top of the balloon, or — CAS 21 第三十八条 — the unguaranteed residual, which is
        // part of the lessor's net investment the chain must unwind but never becomes cash (§9.4 keeps
        // the event table cash-only). Different stream, therefore a different root, therefore its own
        // solve: that is the caliber difference, and it is the only reason this run pays for two.
        return IrrCalculator.periodIrr(stream);
    }

    /** Resolves a convention code, failing with FCS-ENG-1001 instead of the SPI's NOT_FOUND. */
    private static <T extends io.github.cuukenn.fincalc.common.spi.OrderedExtension> T requireConvention(
            ExtensionRegistry<T> registry, String code, String kind) {
        try {
            return registry.require(code);
        } catch (BusinessException ex) {
            throw new BusinessException(EngineErrorCode.CALCULATION_CONTEXT_INVALID, ex,
                    kind + "=" + code);
        }
    }

    /**
     * Periods per year for the metric run; {@code 0} for {@code IRREGULAR}, which the metric layer
     * reads as "no annual convention" — the annualizing metrics return {@code null} and the run
     * carries a WARN instead of an annualized number whose unit is wrong by construction.
     */
    private static int periodsPerYear(LeaseElement element) {
        return element.frequency().periodsPerYear();
    }

    /** The external directory's {@code .fcs} files, sorted; missing or unreadable fails startup. */
    private static List<Path> listScripts(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new BusinessException(EngineErrorCode.FUNCTION_SCRIPT_DIR_LOAD_FAILED,
                    dir.toString());
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".fcs"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException | UncheckedIOException ex) {
            throw new BusinessException(EngineErrorCode.FUNCTION_SCRIPT_DIR_LOAD_FAILED,
                    dir.toString());
        }
    }
}
