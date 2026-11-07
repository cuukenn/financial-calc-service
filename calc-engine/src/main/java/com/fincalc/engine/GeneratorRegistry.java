package com.fincalc.engine;

import com.fincalc.common.error.BusinessException;
import com.fincalc.common.spi.ExtensionRegistry;
import com.fincalc.domain.port.ScheduleGenerator;
import com.fincalc.domain.port.RuleSetView;
import com.fincalc.domain.support.algo.EqualInstallmentGenerator;
import com.fincalc.domain.support.algo.EqualPrincipalGenerator;
import com.fincalc.domain.support.algo.InterestFirstGenerator;
import com.fincalc.domain.support.algo.PrincipalFirstGenerator;
import com.fincalc.rule.generator.RuleDrivenGenerator;
import java.util.Objects;

/**
 * Registry of the {@link ScheduleGenerator}s the engine can route to, keyed by
 * {@link ScheduleGenerator#code()} — which matches the {@code RepaymentMethodId} name for the
 * built-in algorithms and {@code RULE_DRIVEN} for the rule-driven generator.
 *
 * <p>{@link #defaults()} registers the four built-in amortization algorithms. The rule-driven
 * generator is deliberately absent from it: {@link RuleDrivenGenerator} is bound to one compiled
 * rule set at construction, so it cannot be a shared singleton. A run that needs it obtains a
 * private registry through {@link #withRuleDriven(RuleSetView)}, which carries the four built-ins
 * plus a rule-driven generator bound to that run's set — the registry the facade routes a
 * {@code RULE_DRIVEN} request through (orchestration step 2).
 *
 * <p>{@link #require} fails with {@link EngineErrorCode#GENERATOR_NOT_REGISTERED} — an
 * {@code FCS-ENG} code, not the SPI registry's generic {@code NOT_FOUND} — because a missing
 * generator is an engine routing decision, not an extension lookup detail.
 */
public final class GeneratorRegistry {

    private final ExtensionRegistry<ScheduleGenerator> extensions;

    /** An empty registry, for a caller that assembles its own generator set. */
    public GeneratorRegistry() {
        this(ExtensionRegistry.empty());
    }

    private GeneratorRegistry(ExtensionRegistry<ScheduleGenerator> extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
    }

    /**
     * The four built-in amortization algorithms: equal installment, equal principal, interest
     * first and principal first. The rule-driven generator is not among them — see the class
     * javadoc.
     *
     * @return a fresh registry with the built-ins registered
     */
    public static GeneratorRegistry defaults() {
        GeneratorRegistry registry = new GeneratorRegistry();
        registry.extensions.register(new EqualInstallmentGenerator());
        registry.extensions.register(new EqualPrincipalGenerator());
        registry.extensions.register(new InterestFirstGenerator());
        registry.extensions.register(new PrincipalFirstGenerator());
        return registry;
    }

    /**
     * Registers a generator, replacing one already registered under the same code.
     *
     * @param generator the generator; never {@code null}
     * @return this registry, for chaining
     */
    public GeneratorRegistry register(ScheduleGenerator generator) {
        extensions.register(generator);
        return this;
    }

    /**
     * A registry carrying the four built-ins (or whatever this registry holds) plus a
     * {@link RuleDrivenGenerator} bound to {@code ruleSet}. The receiver is not mutated: rule
     * sets differ per run, so each RULE_DRIVEN run gets its own registry view.
     *
     * @param ruleSet the compiled rule set the generator resolves against; never {@code null}
     * @return a new registry with the rule-driven generator registered under {@code RULE_DRIVEN}
     */
    public GeneratorRegistry withRuleDriven(RuleSetView ruleSet) {
        GeneratorRegistry next = new GeneratorRegistry();
        for (ScheduleGenerator generator : extensions.all()) {
            next.extensions.register(generator);
        }
        next.extensions.register(new RuleDrivenGenerator(ruleSet));
        return next;
    }

    /**
     * The generator registered under {@code code}.
     *
     * @param code generator code, matching a {@code RepaymentMethodId} name or a rule-driven code
     * @return the registered generator; never {@code null}
     * @throws BusinessException with {@link EngineErrorCode#GENERATOR_NOT_REGISTERED} when no
     *                           generator is registered under {@code code}
     */
    public ScheduleGenerator require(String code) {
        return extensions.find(code)
                .orElseThrow(() -> new BusinessException(
                        EngineErrorCode.GENERATOR_NOT_REGISTERED, code));
    }
}
