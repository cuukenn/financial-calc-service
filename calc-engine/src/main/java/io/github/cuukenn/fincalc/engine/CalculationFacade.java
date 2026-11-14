package io.github.cuukenn.fincalc.engine;

import io.github.cuukenn.fincalc.common.error.BusinessException;
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
import io.github.cuukenn.fincalc.domain.model.EffectiveInterestTable;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
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
import io.github.cuukenn.fincalc.domain.support.NumericConfig;
import io.github.cuukenn.fincalc.domain.support.ValidatorChain;
import io.github.cuukenn.fincalc.domain.support.cashflow.StandardCashFlowBuilder;
import io.github.cuukenn.fincalc.rule.function.FunctionRegistry;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *       ambiguous or absent ({@code FCS-ENG-1005}, ruling R30 posture); the partial-rule-set
 *       fallback periods ({@code FCS-RULE-2009}, arch-seams 收口 2026-11-14) are disclosed by
 *       the rule-driven generator itself on the raw schedule, so no engine sweep reads the
 *       installments' memo strings.</li>
 *   <li><b>Step 7</b> — the §9.7 effective-interest table; see the {@link EffectiveInterestCalculator}
 *       javadoc for the rate decision: the chain prices the <b>rent-consistent periodic rate</b> —
 *       the IRR of the exact stream the chain itself prices (the opening net investment at t0, the
 *       rent legs, and the terminal net investment of R52's identity, which carries the residual
 *       money the schedule never amortizes) — and reuses an already-solved root of the same stream
 *       off the run's {@link PeriodicRootHandoff} ledger. A missing rate or a table that
 *       cannot be built answers {@code null} with a WARN issue — never an exception, because IRR
 *       non-convergence is an expected outcome.</li>
 *   <li><b>Step 8</b> — return {@code CalculationResult(runId, schedule, effectiveInterest)};
 *       {@code runId} is {@code UUID.randomUUID()} without dashes.</li>
 * </ol>
 *
 * <p>Step 7's caliber — which stream the chain prices, what the terminal net investment carries
 * (CAS 21 第三十八条/第三十九条), the one-solve-per-stream rule and the
 * unrounded-root reuse that keeps every §9.7 row cent-for-cent — lives with its code in
 * {@link EffectiveInterestCalculator#unrecoveredResiduals} and
 * {@link EffectiveInterestCalculator#netInvestmentPeriodIrr} (arch-seams 收口，2026-11-14); the
 * facade owns only the orchestration: IRR-missing and no-root refusals, the calculate call, and
 * the every-refusal-is-a-WARN contract below.
 *
 * <p><b>Known v1 scope notes.</b> The multi-root tie-break「取最接近名义利率的最小正根」is not
 * implemented: it needs {@code element.nominalRate()} inside the solver (R30); the facade keeps
 * {@link IrrCalculator#hasSingleSignChange} as the ambiguity signal and reports
 * {@code FCS-ENG-1005} instead. Deferred/unpaid interest is not surfaced on {@link Schedule}
 * (Task 16 note) and the rule rows' {@code rateBase} column is carried but not implemented
 * (Task 28 note) — both stay tracked. Instances are immutable and safe to share.
 */
public final class CalculationFacade {

    private static final Logger log = LoggerFactory.getLogger(CalculationFacade.class);

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
        List<Path> scripts = listScripts(externalDir);
        FunctionRegistry functions = FunctionScriptLoader.loadClasspath(CLASSPATH_SCRIPT_PREFIX,
                FunctionRegistry.defaults());
        functions = FunctionScriptLoader.load(scripts, functions);
        log.info("function registry assembled: built-in library + {} classpath prefixes + {} external scripts",
                CLASSPATH_SCRIPT_PREFIX, scripts.size());
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
        return calculate(request, NumericConfig.defaults());
    }

    /** 经 {@code NumericConfig} 提供求解逼近次数/容差/求根区间的重载；null 取默认。 */
    public CalculationResult calculate(CalculationRequest request, NumericConfig config) {
        Objects.requireNonNull(request, "request");
        NumericConfig numericConfig = config == null ? NumericConfig.defaults() : config;
        long startNanos = System.nanoTime();
        LeaseElement element = request.element();
        log.info("calculation start: method={}, periods={}, frequency={}, product={}",
                element.method(), element.periods(), element.frequency(),
                request.options().product());

        // Step 1: the calculation context (口径/滚动缺失 → FCS-ENG-1001).
        DayCountConvention dayCount = requireConvention(DayCountConventions.all(),
                element.dayCountCode(), "dayCount");
        DateRollConvention dateRoll = requireConvention(DateRollConventions.all(),
                element.dateRollCode(), "dateRoll");
        log.debug("step 1 context resolved: dayCount={}, dateRoll={}",
                element.dayCountCode(), element.dateRollCode());

        // Step 2: route to the generator and produce the raw schedule.
        GeneratorRegistry generators = this.generators;
        if (element.method() == RepaymentMethodId.RULE_DRIVEN) {
            if (request.ruleSet() == null) {
                throw new BusinessException(EngineErrorCode.RULE_DRIVEN_REQUIRES_RULE_SET);
            }
            generators = generators.withRuleDriven(request.ruleSet());
        }
        CalculationContext context = new CalculationContext(element, request.ruleSet(), dayCount,
                dateRoll, SystemClockProvider.INSTANCE, request.options(), numericConfig);
        Schedule schedule = generators.require(element.method().name()).generate(context);
        log.debug("step 2 schedule generated: generator={}, installments={}, issues={}",
                element.method().name(), schedule.installments().size(), schedule.issues().size());

        // Step 3: the §9.4 event table.
        List<CashFlow> flows = cashFlowBuilder.build(schedule, element, request.options());
        log.debug("step 3 cash flows built: {} events", flows.size());

        // Step 4: the §9.5 metric run (rates unconfigured — derived inside the metric layer). The
        // perspective is the caller's (R103); it changes nothing above, and the schedule keeps the
        // lessor event table whatever the metric view is (R109).
        List<String> failedMetrics = new ArrayList<>();
        MetricContext metricContext = new MetricContext(flows, schedule, element,
                periodsPerYear(element), null, null, null, request.perspective(), numericConfig);
        FinancialMetrics metrics = this.metrics.computeAll(metricContext, failedMetrics);
        log.debug("step 4 metrics computed: {} failed", failedMetrics.size());

        // Step 5: the enriched schedule.
        schedule = schedule.with(metrics, flows);

        // Step 6: validation plus the engine's own diagnostics.
        List<ValidationIssue> engineIssues = new ArrayList<>();
        // A1（form-field-gaps 15）：产品身份只在这里生效——选校验链。测算内核保持
        // 产品盲（R100）；LEASE / 未声明产品的 run 走缺省链，issues 与 v1 逐字节一致。
        schedule = (request.options().product() == ProductProfile.MORTGAGE
                ? mortgageValidators : validators).validate(schedule, element);
        for (String code : failedMetrics) {
            log.warn("metric {} not computable (FCS-ENG-1007): recorded as WARN on the run", code);
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
            log.warn("flow set has no unambiguous IRR root (FCS-ENG-1005)");
            engineIssues.add(new ValidationIssue(IssueLevel.WARN,
                    EngineErrorCode.IRR_ROOT_AMBIGUOUS.code(),
                    EngineErrorCode.IRR_ROOT_AMBIGUOUS.message(), null));
        }

        // Step 7: the §9.7 effective-interest table (nullable, never thrown).
        EffectiveInterestTable effectiveInterest = effectiveInterest(schedule, element, metrics,
                metricContext.rootHandoff(), engineIssues, numericConfig);

        // The engine's findings join the schedule's own issues in one rebuild.
        if (!engineIssues.isEmpty()) {
            List<ValidationIssue> combined = new ArrayList<>(schedule.issues());
            combined.addAll(engineIssues);
            schedule = new Schedule(schedule.header(), schedule.installments(),
                    schedule.cashFlows(), combined);
        }

        // Step 8: the result.
        String runId = UUID.randomUUID().toString().replace("-", "");
        log.info("calculation done: runId={}, durationMs={}, installments={}, issues={}, "
                        + "effectiveInterest={}",
                runId, (System.nanoTime() - startNanos) / 1_000_000, schedule.installments().size(),
                schedule.issues().size(), effectiveInterest != null);
        return new CalculationResult(runId, schedule, effectiveInterest);
    }

    /**
     * Step 7: the §9.7 table at the rent-consistent rate. The rate decision itself — which stream
     * the chain prices, what the terminal net investment carries (CAS 21 第三十八条/第三十九条),
     * and the one-solve-per-stream ledger reuse — is the {@link EffectiveInterestCalculator}'s
     * caliber ({@code unrecoveredResiduals} / {@code netInvestmentPeriodIrr}, arch-seams 收口
     * 2026-11-14); the facade only orchestrates: it turns every refusal path into {@code null}
     * plus a WARN issue carrying the stable code of the refusal — the run itself never fails here.
     */
    private EffectiveInterestTable effectiveInterest(Schedule schedule, LeaseElement element,
            FinancialMetrics metrics, PeriodicRootHandoff rootHandoff, List<ValidationIssue> issues,
            NumericConfig numericConfig) {
        if (metrics.find(MetricCodes.IRR_PERIOD) == null) {
            log.warn("effective-interest table skipped: IRR_PERIOD did not converge ({})",
                    CalcErrorCode.IRR_NOT_CONVERGED.code());
            issues.add(new ValidationIssue(IssueLevel.WARN, CalcErrorCode.IRR_NOT_CONVERGED.code(),
                    CalcErrorCode.IRR_NOT_CONVERGED.code()
                            + ": IRR_PERIOD 不收敛, 实际利率法摊销表未生成", null));
            return null;
        }
        BigDecimal residualExtra = EffectiveInterestCalculator.unrecoveredResiduals(element);
        BigDecimal periodIrr = EffectiveInterestCalculator.netInvestmentPeriodIrr(schedule,
                residualExtra, rootHandoff, numericConfig);
        if (periodIrr == null) {
            log.warn("effective-interest table skipped: rent-consistent stream has no root ({})",
                    CalcErrorCode.IRR_NOT_CONVERGED.code());
            issues.add(new ValidationIssue(IssueLevel.WARN, CalcErrorCode.IRR_NOT_CONVERGED.code(),
                    CalcErrorCode.IRR_NOT_CONVERGED.code()
                            + ": 租金口径现金流无内部收益率, 实际利率法摊销表未生成", null));
            return null;
        }
        try {
            return EffectiveInterestCalculator.calculate(schedule, periodIrr, residualExtra,
                    numericConfig);
        } catch (BusinessException ex) {
            log.warn("effective-interest table refused ({}): {}", ex.errorCode().code(),
                    ex.getMessage());
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
