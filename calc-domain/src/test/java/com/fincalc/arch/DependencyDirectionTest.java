package com.fincalc.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DependencyDirectionTest {
    private final JavaClasses classes =
            new ClassFileImporter().importPackages("com.fincalc");

    @Test
    void domain_must_not_depend_on_frameworks() {
        // com.fincalc.domain is empty until the domain model lands; ArchUnit 1.x rejects
        // rules whose `that()` clause matches nothing, so allow an empty match for now.
        // The rule becomes effective as soon as the first domain class exists.
        ArchRule rule = noClasses().that().resideInAPackage("com.fincalc.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "org.apache.poi..",
                        "org.apache.ibatis..", "com.fasterxml.jackson..")
                .allowEmptyShould(true);
        rule.check(classes);
    }
}
