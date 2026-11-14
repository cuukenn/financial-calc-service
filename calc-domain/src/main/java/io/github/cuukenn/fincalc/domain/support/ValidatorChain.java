package io.github.cuukenn.fincalc.domain.support;

import io.github.cuukenn.fincalc.common.spi.ExtensionRegistry;
import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.port.Validator;
import io.github.cuukenn.fincalc.domain.support.validate.DateMonotonicValidator;
import io.github.cuukenn.fincalc.domain.support.validate.MortgageThresholdValidator;
import io.github.cuukenn.fincalc.domain.support.validate.PeriodIdentityValidator;
import io.github.cuukenn.fincalc.domain.support.validate.PrincipalConservationValidator;
import io.github.cuukenn.fincalc.domain.support.validate.RentCompositionValidator;
import io.github.cuukenn.fincalc.domain.support.validate.TailBalanceValidator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ordered collection of {@link Validator}s run over a finished schedule.
 *
 * <p>{@link #validate(Schedule, LeaseElement)} never mutates the schedule it was given: it returns a
 * new {@link Schedule} carrying the findings, so a schedule that a caller still holds stays intact.
 *
 * <p>A generator already reports the findings it can see while it builds the plan table. To keep the
 * audit trail readable, the chain reports at most one finding per error code and period: a finding the
 * schedule already carries is not reported a second time. A validator therefore expresses everything
 * it found for one period in a single {@link ValidationIssue}, naming each rule that period broke.
 */
public final class ValidatorChain {

    private final ExtensionRegistry<Validator> registry;

    public ValidatorChain(ExtensionRegistry<Validator> registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * The chain every built-in calculation uses, in the code-number order: principal
     * conservation ({@link CalcErrorCode#PRINCIPAL_CONSERVATION_BROKEN}), tail balance
     * ({@link CalcErrorCode#TAIL_BALANCE_MISMATCH}), per-period identity
     * ({@link CalcErrorCode#PERIOD_IDENTITY_BROKEN}), rent composition
     * ({@link CalcErrorCode#RENT_COMPOSITION_BROKEN}) and, last, the period-date monotonicity net
     * ({@link CalcErrorCode#PERIOD_DATE_ORDER_INVALID}). The registration order below is the order
     * {@link #codes()} reports, and {@code ValidatorChainTest} pins it.
     */
    public static ValidatorChain defaults() {
        ExtensionRegistry<Validator> validators = ExtensionRegistry.empty();
        validators.register(new PrincipalConservationValidator());
        validators.register(new TailBalanceValidator());
        validators.register(new PeriodIdentityValidator());
        validators.register(new RentCompositionValidator());
        validators.register(new DateMonotonicValidator());
        return new ValidatorChain(validators);
    }

    /**
     * The mortgage chain: the defaults chain plus the mortgage threshold disclosures
     * ({@code MortgageThresholdValidator}, order 100 — after every conservation net). Assembled
     * only for runs declaring {@code ProductProfile#MORTGAGE} (form-field-gaps 15 / A2); the
     * defaults chain stays the byte-identical v1 path for every other run.
     */
    public static ValidatorChain withMortgageThresholds() {
        ExtensionRegistry<Validator> validators = ExtensionRegistry.empty();
        for (Validator validator : defaults().registry.all()) {
            validators.register(validator);
        }
        validators.register(new MortgageThresholdValidator());
        return new ValidatorChain(validators);
    }

    /** Codes of the validators in this chain, in the order they run. */
    public List<String> codes() {
        List<Validator> all = registry.all();
        List<String> codes = new ArrayList<>(all.size());
        for (Validator validator : all) {
            codes.add(validator.code());
        }
        return Collections.unmodifiableList(codes);
    }

    /** Findings of every validator in this chain, excluding findings the schedule already carries. */
    public List<ValidationIssue> validateAll(Schedule schedule, LeaseElement element) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(element, "element");

        Set<String> alreadyCarried = new HashSet<>();
        for (ValidationIssue existing : schedule.issues()) {
            alreadyCarried.add(key(existing));
        }

        List<ValidationIssue> findings = new ArrayList<>();
        for (Validator validator : registry.all()) {
            List<ValidationIssue> issues = validator.validate(schedule, element);
            if (issues == null) {
                continue;
            }
            for (ValidationIssue issue : issues) {
                if (issue == null || !alreadyCarried.add(key(issue))) {
                    continue;
                }
                findings.add(issue);
            }
        }
        return Collections.unmodifiableList(findings);
    }

    /**
     * A schedule carrying both the issues it already had and the newly found ones. When nothing new
     * was found the given schedule is returned unchanged — {@link Schedule} is immutable, so an
     * identical instance is a valid answer and callers must not rely on object identity changing.
     */
    public Schedule validate(Schedule schedule, LeaseElement element) {
        List<ValidationIssue> findings = validateAll(schedule, element);
        if (findings.isEmpty()) {
            return schedule;
        }
        List<ValidationIssue> merged = new ArrayList<>(schedule.issues());
        merged.addAll(findings);
        return new Schedule(schedule.header(), schedule.installments(), schedule.cashFlows(), merged);
    }

    private static String key(ValidationIssue issue) {
        return issue.code() + "#" + issue.periodNo();
    }
}
