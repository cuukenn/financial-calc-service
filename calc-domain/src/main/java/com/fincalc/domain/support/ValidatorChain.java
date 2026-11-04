package com.fincalc.domain.support;

import com.fincalc.common.spi.ExtensionRegistry;
import com.fincalc.domain.model.LeaseElement;
import com.fincalc.domain.model.Schedule;
import com.fincalc.domain.model.ValidationIssue;
import com.fincalc.domain.port.Validator;
import com.fincalc.domain.support.validate.DateMonotonicValidator;
import com.fincalc.domain.support.validate.PrincipalConservationValidator;
import com.fincalc.domain.support.validate.TailBalanceValidator;
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

    /** The chain every built-in calculation uses: principal, tail balance and period dates. */
    public static ValidatorChain defaults() {
        ExtensionRegistry<Validator> validators = ExtensionRegistry.empty();
        validators.register(new PrincipalConservationValidator());
        validators.register(new TailBalanceValidator());
        validators.register(new DateMonotonicValidator());
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
